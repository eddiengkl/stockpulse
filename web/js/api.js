/*
 * StockPulse — api.js
 *
 * The only place in the frontend that talks to the Java backend.
 *
 * Rules this file keeps:
 *   1. Every response is unwrapped here. Callers receive the `data` payload and
 *      never see the {ok, data} / {ok, error} envelope, so no screen can
 *      accidentally start reading a field out of the wrong level.
 *   2. The demo role is attached to every request as X-Demo-Role. The server
 *      reads it, trims it, and enforces it in Java. This file does not decide
 *      what a role may do — it only says which role the person picked.
 *   3. A refusal is thrown, not returned. The Java message is carried through
 *      unchanged so the page can show exactly what Java said.
 *
 * Nothing here calculates anything about stock, money, risk or dates. The
 * backend is the only source of figures.
 */

(function (global) {
  'use strict';

  /** Header the server reads. Mirrors server.ApiRequest.ROLE_HEADER. */
  var ROLE_HEADER = 'X-Demo-Role';

  /** The two roles the server recognises. Mirrors service.InventoryService. */
  var ROLE_MANAGER = 'Manager';
  var ROLE_STAFF = 'Staff';

  /**
   * A refusal or a transport failure, in one shape.
   *
   * `code` is the server's error code — the Java exception class name, such as
   * "InsufficientStockException" — so a caller can branch on the kind of
   * failure without matching on message wording that may be reworded later.
   * `status` is 0 when the request never reached the server.
   */
  function ApiError(status, code, message, detail) {
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.message = message || 'The request failed.';
    this.detail = detail || null;
  }

  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  /** True when the server refused because the role was too low. */
  ApiError.prototype.isRoleRefusal = function () {
    return this.status === 403 || this.code === 'RoleNotPermittedException';
  };

  /** True when the request never reached the server at all. */
  ApiError.prototype.isOffline = function () {
    return this.status === 0;
  };

  /** Text for a person: the Java message, plus the field name when there is one. */
  ApiError.prototype.describe = function () {
    var text = this.message;
    if (this.detail && this.detail.indexOf('field=') === 0) {
      text += ' (' + this.detail.slice('field='.length) + ')';
    }
    return text;
  };

  /**
   * Holds the current demo role and the last-known write lock, and performs
   * the actual fetch calls.
   *
   * The role is state, not an argument passed at every call site: the picker
   * changes it once and every later request follows. That mirrors the server,
   * which derives `canManage` from the header on the request in front of it.
   */
  function Api() {
    this.role = ROLE_STAFF;
  }

  /** The role sent on the next request. */
  Api.prototype.setRole = function (role) {
    this.role = (role === ROLE_MANAGER) ? ROLE_MANAGER : ROLE_STAFF;
  };

  Api.prototype.isManager = function () {
    return this.role === ROLE_MANAGER;
  };

  /**
   * One request, unwrapped.
   *
   * @param {string} method  GET, POST or PUT
   * @param {string} path    an absolute path beginning with /api
   * @param {object} [body]  form fields, sent urlencoded as the server expects
   * @returns {Promise<*>}   the envelope's `data`, whatever shape that is
   */
  Api.prototype.request = function (method, path, body) {
    var headers = {};
    headers[ROLE_HEADER] = this.role;

    var options = {
      method: method,
      headers: headers,
      // no-store is also sent by the server; asking here keeps a browser from
      // serving a stale dashboard after a stock action.
      cache: 'no-store'
    };

    if (body) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
      options.body = encodeForm(body);
    }

    return fetch(path, options).then(function (response) {
      return response.text().then(function (text) {
        return interpret(response.status, text);
      });
    }, function () {
      // fetch rejects only on a transport problem, never on a 4xx or 5xx.
      throw new ApiError(0, 'ServerUnreachable',
        'The StockPulse server is not answering. Check that it is running, then reload.',
        'The request to ' + path + ' did not reach the server.');
    });
  };

  Api.prototype.get = function (path) {
    return this.request('GET', path);
  };

  Api.prototype.post = function (path, body) {
    return this.request('POST', path, body);
  };

  Api.prototype.put = function (path, body) {
    return this.request('PUT', path, body);
  };

  /**
   * Turns a raw response into either the `data` payload or an ApiError.
   *
   * A 200 that is not JSON means the request never reached the API handler —
   * a wrong path would have been answered by the static file handler instead.
   * Saying so plainly is better than reporting a JSON syntax error.
   */
  function interpret(status, text) {
    var payload = null;
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch (e) {
        payload = null;
      }
    }

    if (payload && payload.ok === true) {
      return payload.data;
    }

    if (payload && payload.ok === false && payload.error) {
      var error = payload.error;
      throw new ApiError(status, error.code, error.message, error.detail);
    }

    if (payload === null) {
      throw new ApiError(status, 'NotJson',
        'The server answered with something that is not the API.',
        'HTTP ' + status + ' from a path that may not be an API route.');
    }

    throw new ApiError(status, 'UnexpectedEnvelope',
      'The server answered in a shape this page does not recognise.',
      'HTTP ' + status);
  }

  /** Form-encodes plain values, which is what RequestMap.parse expects. */
  function encodeForm(fields) {
    var parts = [];
    var names = Object.keys(fields);
    for (var i = 0; i < names.length; i++) {
      var value = fields[names[i]];
      if (value === undefined || value === null) {
        continue;
      }
      parts.push(encodeURIComponent(names[i]) + '=' + encodeURIComponent(String(value)));
    }
    return parts.join('&');
  }

  /* ------------------------------------------------------------------ routes
   * One named function per endpoint, so no screen has to remember a URL.
   * Every one of these exists in src/server/Routes.java.
   * ------------------------------------------------------------------ */

  /** Settings, seasons, the effective date and what this role may do. */
  Api.prototype.bootstrap = function () {
    return this.get('/api/bootstrap');
  };

  /** Every item with its figures, the ranked warnings, and the totals. */
  Api.prototype.dashboard = function () {
    return this.get('/api/dashboard');
  };

  /** One item with its batches and recent sales. */
  Api.prototype.item = function (itemId) {
    return this.get('/api/items/' + encodeURIComponent(itemId));
  };

  /** All items, optionally narrowed by the server-side search. */
  Api.prototype.items = function (options) {
    var query = buildQuery(options);
    return this.get('/api/items' + query);
  };

  /** The recorded activity log, newest first, as the server returns it. */
  Api.prototype.activity = function () {
    return this.get('/api/activity');
  };

  /** The advisor briefing. Always answers; may report that no AI was used. */
  Api.prototype.briefing = function () {
    return this.get('/api/advisor/briefing');
  };

  Api.prototype.sale = function (itemId, fields) {
    return this.post('/api/items/' + encodeURIComponent(itemId) + '/sale', fields);
  };

  Api.prototype.undoSale = function (itemId, fields) {
    return this.post('/api/items/' + encodeURIComponent(itemId) + '/undo-sale', fields);
  };

  Api.prototype.delivery = function (itemId, fields) {
    return this.post('/api/items/' + encodeURIComponent(itemId) + '/delivery', fields);
  };

  Api.prototype.waste = function (itemId, fields) {
    return this.post('/api/items/' + encodeURIComponent(itemId) + '/waste', fields);
  };

  Api.prototype.addItem = function (fields) {
    return this.post('/api/items', fields);
  };

  Api.prototype.updateItem = function (itemId, fields) {
    return this.put('/api/items/' + encodeURIComponent(itemId), fields);
  };

  Api.prototype.saveSettings = function (fields) {
    return this.put('/api/settings', fields);
  };

  Api.prototype.saveSeason = function (name, fields) {
    return this.put('/api/seasons/' + encodeURIComponent(name), fields);
  };

  function buildQuery(options) {
    if (!options) {
      return '';
    }
    var names = Object.keys(options);
    var parts = [];
    for (var i = 0; i < names.length; i++) {
      var value = options[names[i]];
      if (value === undefined || value === null || value === '') {
        continue;
      }
      parts.push(encodeURIComponent(names[i]) + '=' + encodeURIComponent(String(value)));
    }
    return parts.length ? '?' + parts.join('&') : '';
  }

  global.StockPulseApi = {
    Api: Api,
    ApiError: ApiError,
    ROLE_HEADER: ROLE_HEADER,
    ROLE_MANAGER: ROLE_MANAGER,
    ROLE_STAFF: ROLE_STAFF,
    create: function () {
      return new Api();
    }
  };
}(window));
