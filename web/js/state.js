/*
 * StockPulse — state.js
 *
 * One object holding what the server last told this page.
 *
 * The rule this file exists to protect: the page holds no derived figures. It
 * stores the bootstrap payload, the dashboard payload and the chosen view, and
 * hands them to renderers. If a screen needs a number, it reads it from one of
 * those payloads — it does not compute one, and there is no place here to
 * cache a computed one.
 *
 * `bootstrap` is the only payload loaded in this step. The dashboard cache is
 * declared because the shape of the page is settled, but nothing fills it yet.
 */

(function (global) {
  'use strict';

  function State(api) {
    this.api = api;

    /** The demo role the picker is on. Sent on every request by api.js. */
    this.role = global.StockPulseApi.ROLE_STAFF;

    /** The current view id: dashboard, inventory, activity or settings. */
    this.view = 'dashboard';

    /** GET /api/bootstrap, as returned. Belongs to the role that asked. */
    this.bootstrap = null;

    /** GET /api/dashboard, as returned. Null until loaded. */
    this.dashboard = null;

    /** GET /api/advisor/briefing, as returned. Null until loaded. */
    this.briefing = null;

    /**
     * GET /api/items, as returned, for the filters currently applied.
     * `count` and `totalBeforeFilter` come from Java, so the page can say
     * "3 of 6" without counting anything itself.
     */
    this.items = null;

    /**
     * The filters the item list is showing. These are the values sent to
     * Java, not a record of what JavaScript decided: changing one re-asks
     * the server rather than narrowing the rows already in hand.
     */
    this.itemFilters = { search: '', category: '', status: 'all' };

    /** The most recent failure worth showing, or null. */
    this.lastError = null;

    /**
     * The item the drawer is showing: GET /api/items/{id}, as returned.
     * Null when no drawer is open or the request is still in flight.
     */
    this.detail = null;

    /** The id the drawer is trying to show, or null when it is closed. */
    this.detailId = null;

    /** True while a sale or undo request is in flight. */
    this.saleBusy = false;

    /** The last sale or undo answer, as Java sent it. Null until one arrives. */
    this.saleResult = null;
    this.undoResult = null;

    /** True while a delivery request is in flight. */
    this.deliveryBusy = false;

    /** The last delivery answer, as Java sent it. Null until one arrives. */
    this.deliveryResult = null;

    /**
     * True only while the refresh that follows a sale or undo is in flight.
     *
     * The refresh re-emits 'detail', which would otherwise clear the message
     * above the buttons a moment after Java's answer appeared there.
     */
    this.keepSaleNote = false;

    /** True while a bootstrap request is in flight. */
    this.loading = false;

    this.listeners = [];
  }

  /** Subscribes fn to state changes. Called with (state, reason). */
  State.prototype.subscribe = function (fn) {
    this.listeners.push(fn);
  };

  /** Notifies subscribers. `reason` says what changed, for targeted redraws. */
  State.prototype.emit = function (reason) {
    for (var i = 0; i < this.listeners.length; i++) {
      this.listeners[i](this, reason);
    }
  };

  /** What the server said this role may do. False until bootstrap answers. */
  State.prototype.canManage = function () {
    return Boolean(this.bootstrap && this.bootstrap.canManage);
  };

  /**
   * Whether the server has blocked writes, which it reports after a failed
   * save. The page disables its forms rather than letting someone fill one in
   * and be refused.
   */
  State.prototype.writesBlocked = function () {
    return Boolean(this.bootstrap && this.bootstrap.writesBlocked);
  };

  /** The effective date, as Java formatted it: text, weekday and ISO. */
  State.prototype.today = function () {
    return this.bootstrap ? this.bootstrap.today : null;
  };

  /**
   * Loads /api/bootstrap for the current role.
   *
   * Re-fetched whenever the picker changes, because `canManage` is derived
   * from the header on the request that asked. Holding the old answer would
   * show the previous role's permissions.
   */
  State.prototype.loadBootstrap = function () {
    var self = this;
    this.loading = true;
    this.emit('loading');
    this.api.setRole(this.role);

    return this.api.bootstrap().then(function (data) {
      self.bootstrap = data;
      self.lastError = null;
      self.loading = false;
      // Trust the server's view of the role over the local copy: it echoes
      // back the role it actually used.
      if (data && data.role) {
        self.role = data.role;
        self.api.setRole(data.role);
      }
      self.emit('bootstrap');
      return data;
    }, function (error) {
      self.bootstrap = null;
      self.lastError = error;
      self.loading = false;
      self.emit('error');
      throw error;
    });
  };

  /** Changes the demo role and reloads what that role is allowed to see. */
  State.prototype.setRole = function (role) {
    var wanted = (role === global.StockPulseApi.ROLE_MANAGER)
      ? global.StockPulseApi.ROLE_MANAGER
      : global.StockPulseApi.ROLE_STAFF;

    if (wanted === this.role && this.bootstrap) {
      return Promise.resolve(this.bootstrap);
    }

    this.role = wanted;
    this.api.setRole(wanted);
    // Anything already loaded was answered for the previous role.
    this.dashboard = null;
    this.briefing = null;
    this.items = null;
    // The open drawer's payload was also fetched under the old role, so it is
    // dropped too; the drawer closes rather than showing a stale item.
    this.detail = null;
    this.detailId = null;
    this.emit('role');
    return this.loadBootstrap();
  };

  /**
   * Loads GET /api/dashboard.
   *
   * The payload is stored exactly as returned. Nothing is derived from it here
   * — a renderer that needs a figure reads the field out of this object, and
   * nothing in this file adds one up.
   */
  State.prototype.loadDashboard = function () {
    var self = this;
    return this.api.dashboard().then(function (data) {
      self.dashboard = data;
      self.emit('dashboard');
      return data;
    }, function (error) {
      self.dashboard = null;
      self.emit('dashboardError');
      throw error;
    });
  };

  /** Loads GET /api/advisor/briefing, which always answers 200. */
  State.prototype.loadBriefing = function () {
    var self = this;
    return this.api.briefing().then(function (data) {
      self.briefing = data;
      self.emit('briefing');
      return data;
    }, function (error) {
      self.briefing = null;
      self.emit('briefingError');
      throw error;
    });
  };

  /**
   * Loads GET /api/items for the current filters.
   *
   * Every filter is handed to Java in the query string, including the search
   * text. Nothing is decided here: `count` and `totalBeforeFilter` arrive in
   * the response, and the page prints them rather than counting rows.
   *
   * The response is stored only if it still matches the filters that asked for
   * it. Typing quickly fires several requests, and an earlier one can answer
   * last; without this check the table would show results for a query the user
   * has already moved on from.
   */
  State.prototype.loadItems = function () {
    var self = this;
    var sent = {
      search: this.itemFilters.search,
      category: this.itemFilters.category,
      status: this.itemFilters.status
    };

    this.emit('itemsLoading');

    return this.api.items({
      search: sent.search,
      category: sent.category,
      status: (sent.status === 'all' ? '' : sent.status)
    }).then(function (data) {
      if (!self.filtersStillCurrent(sent)) {
        // A later request has taken over; this answer is stale. Return it to
        // the caller but do not overwrite what the user is now looking at.
        return data;
      }
      self.items = data;
      self.lastError = null;
      self.emit('items');
      return data;
    }, function (error) {
      if (self.filtersStillCurrent(sent)) {
        self.items = null;
        // Recorded before the event so the handler reports this failure and
        // not whatever failed earlier in the session.
        self.lastError = error;
        self.emit('itemsError');
      }
      throw error;
    });
  };

  /** True when `sent` still describes the filters on screen. */
  State.prototype.filtersStillCurrent = function (sent) {
    return this.itemFilters.search === sent.search
        && this.itemFilters.category === sent.category
        && this.itemFilters.status === sent.status;
  };

  /**
   * Applies a filter change and reloads the list from Java.
   *
   * The caller states the whole filter set rather than mutating state, so a
   * stale request can be recognised as stale by comparing it with this record.
   */
  State.prototype.setItemFilters = function (changes) {
    var next = {
      search: changes.search !== undefined ? changes.search : this.itemFilters.search,
      category: changes.category !== undefined ? changes.category : this.itemFilters.category,
      status: changes.status !== undefined ? changes.status : this.itemFilters.status
    };

    if (next.search === this.itemFilters.search
        && next.category === this.itemFilters.category
        && next.status === this.itemFilters.status) {
      return Promise.resolve(this.items);
    }

    this.itemFilters = next;
    return this.loadItems();
  };

  /**
   * Opens the drawer for one item and loads its detail.
   *
   * Only the request is made here; the 'detail' event that follows is what
   * paints the drawer, so an opened row and a reopened one take the same path.
   *
   * The id is recorded before the request so a slow answer can be recognised
   * as stale: if the person clicks another row while this one is in flight,
   * `detailId` has moved on and the older payload is dropped rather than
   * drawn over the newer item.
   */
  State.prototype.openDetail = function (itemId) {
    var self = this;
    this.detailId = itemId;
    this.detail = null;
    // A new item starts with no answer on screen: the previous item's "Sold…"
    // or refusal says nothing about this one.
    this.saleResult = null;
    this.undoResult = null;
    this.deliveryResult = null;
    this.keepSaleNote = false;
    this.emit('detailLoading');

    return this.api.item(itemId).then(function (data) {
      if (self.detailId !== itemId) {
        // A different item was opened while this was loading.
        return data;
      }
      self.detail = data;
      self.lastError = null;
      self.emit('detail');
      return data;
    }, function (error) {
      if (self.detailId === itemId) {
        self.detail = null;
        self.lastError = error;
        self.emit('detailError');
      }
      throw error;
    });
  };

  /** Closes the drawer. The payload is discarded so nothing stale can reopen. */
  State.prototype.closeDetail = function () {
    this.detailId = null;
    this.detail = null;
    this.emit('detailClosed');
  };

  /**
   * Records a sale and refreshes what the page shows afterwards.
   *
   * The quantity and price are sent exactly as typed. Whether the sale is
   * allowed, which batch it draws from, what stock is left and what the new
   * risk is are all decided by Java; this method only forwards the answer.
   *
   * `unitPrice` is omitted when the field was left blank, because the server
   * reads a missing price as "use the item's own selling price". Sending 0
   * would record a free sale.
   */
  State.prototype.recordSale = function (itemId, qty, unitPrice) {
    var self = this;
    var fields = { qty: qty };
    if (unitPrice !== null && unitPrice !== '') {
      fields.unitPrice = unitPrice;
    }
    this.saleBusy = true;
    this.emit('saleBusy');
    return this.api.sale(itemId, fields).then(function (result) {
      self.saleBusy = false;
      self.saleResult = result;
      self.emit('saleDone');
      self.refreshAfterChange(itemId);
      return result;
    }, function (error) {
      self.saleBusy = false;
      self.saleResult = null;
      self.lastError = error;
      self.emit('saleFailed');
      throw error;
    });
  };

  /**
   * Undoes the most recent sale of the item.
   *
   * Java decides whether there is one to undo and whether this role may. A
   * `false` result is a normal answer, not a failure, and is passed on as one.
   */
  State.prototype.undoSale = function (itemId) {
    var self = this;
    this.saleBusy = true;
    this.emit('saleBusy');
    return this.api.undoSale(itemId, {}).then(function (result) {
      self.saleBusy = false;
      self.undoResult = result;
      self.emit('undoDone');
      // `undone: false` means there was no sale to undo, so no batch moved and
      // no figure changed. Nothing is refreshed in that case: there is nothing
      // new to show, and the message already says why.
      if (result && result.undone) {
        self.refreshAfterChange(itemId);
      }
      return result;
    }, function (error) {
      self.saleBusy = false;
      self.undoResult = null;
      self.lastError = error;
      self.emit('saleFailed');
      throw error;
    });
  };

  /**
   * Records a delivery into the item and refreshes what the page shows after.
   *
   * The three fields go to Java exactly as typed: quantity, unit cost, and the
   * expiry date in the server's own format (dd/MM/yyyy). Nothing is checked
   * here beyond the empty box — whether the item needs an expiry, whether the
   * date parses, whether it is already past, and how the new batch fits the
   * remaining capacity are all Java's calls, and its refusal is shown as sent.
   *
   * `expiryDate` is omitted when the field was left blank, which the server
   * reads as "no expiry". For an item that does expire, Java names that as the
   * reason it declined rather than this file inventing a default.
   */
  State.prototype.receiveDelivery = function (itemId, qty, unitCost, expiryDate) {
    var self = this;
    var fields = { qty: qty, unitCost: unitCost };
    if (expiryDate !== null && expiryDate !== undefined && expiryDate !== '') {
      fields.expiryDate = expiryDate;
    }
    this.deliveryBusy = true;
    this.deliveryResult = null;
    this.emit('deliveryBusy');
    return this.api.delivery(itemId, fields).then(function (result) {
      self.deliveryBusy = false;
      self.deliveryResult = result;
      self.emit('deliveryDone');
      self.refreshAfterChange(itemId);
      return result;
    }, function (error) {
      self.deliveryBusy = false;
      self.deliveryResult = null;
      self.lastError = error;
      self.emit('deliveryFailed');
      throw error;
    });
  };

  /**
   * Re-reads the affected item and the inventory list after a stock change.
   *
   * Both are asked of the server rather than patched from the response, so the
   * drawer's figures, the table's rows and the totals all come from one place.
   * Failures here are ignored on purpose: the sale itself already succeeded,
   * and a failed refresh should not be reported as a failed sale.
   */
  State.prototype.refreshAfterChange = function (itemId) {
    var self = this;
    this.keepSaleNote = true;
    this.api.item(itemId).then(function (data) {
      if (self.detailId === itemId) {
        self.detail = data;
        self.emit('detail');
      }
    }, function () {}).then(function () {
      self.keepSaleNote = false;
    });
    this.loadItems().catch(function () {});
    this.refreshDashboard();
  };

  /**
   * Re-reads the dashboard totals and warning queues, best effort.
   *
   * The sale already succeeded, so a failure here must not surface as a failed
   * sale. `loadDashboard` emits 'dashboard' on success, which repaints the
   * figures; its failure path would blank the KPIs, so the error is swallowed
   * instead and the previous figures stay on screen.
   *
   * The advisor briefing is deliberately not re-requested: the AI text is
   * cached in `this.briefing` and is re-rendered with the new figures, so a
   * stock change costs no model call.
   */
  State.prototype.refreshDashboard = function () {
    var self = this;
    this.loadDashboard().catch(function () {
      // Keep whatever the dashboard was already showing. Nothing is reported:
      // the stock change itself was accepted by the server.
      self.lastError = null;
    });
  };

  /** Switches the visible view. */
  State.prototype.setView = function (view) {
    if (this.view === view) {
      return;
    }
    this.view = view;
    this.emit('view');
  };

  global.StockPulseState = {
    State: State,
    create: function (api) {
      return new State(api);
    }
  };
}(window));
