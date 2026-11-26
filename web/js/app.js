/*
 * StockPulse — app.js
 *
 * Startup, the demo role picker, the displayed date, the view switcher and
 * the wiring for each screen's controls.
 *
 * What this file does:
 *   - loads GET /api/bootstrap on startup and keeps the answer in state.js
 *   - enables the Manager / Staff picker and re-fetches bootstrap when it changes
 *   - writes the effective date into the top bar, using today.text and
 *     today.weekday exactly as Java formatted them
 *   - switches between the four views, which are already laid out in the shell
 *   - loads the dashboard payload and the advisor briefing (step 3)
 *   - loads GET /api/items and wires the inventory toolbar (step 4)
 *   - opens the item detail drawer from a row and wires its close paths
 *     (step 5)
 *
 * What this file does NOT do: decide any figure. It picks which values to
 * send to Java and hands the answers to the renderers. There is no arithmetic
 * and no filtering here.
 *
 * Still inert, waiting for their steps: the stock actions and the add/edit
 * forms (step 6), the activity log (step 7) and the settings form (step 8).
 * The Add Item button stays disabled throughout.
 */

(function (global) {
  'use strict';

  var Api = global.StockPulseApi;
  var Format = global.StockPulseFormat;
  var StateFactory = global.StockPulseState;

  /** The heading each view shows. Static wording, not data. */
  var VIEW_TEXT = {
    dashboard: {
      title: 'Dashboard',
      subtitle: 'What needs attention today, across all tracked items.'
    },
    inventory: {
      title: 'Inventory',
      subtitle: 'Every tracked item, in the order the server returns them.'
    },
    activity: {
      title: 'Activity',
      subtitle: 'Every recorded action, newest first, as the server returns it.'
    },
    settings: {
      title: 'Settings',
      subtitle: 'The rules that decide how every figure is computed.'
    }
  };

  var state = null;

  /**
   * How long typing settles before the item list is re-requested.
   *
   * This delays a request; it does not filter anything. Every keystroke that
   * survives the delay is sent to Java as a search term.
   */
  var FILTER_DELAY_MS = 250;

  /* ------------------------------------------------------------------ boot */

  function boot() {
    var api = Api.create();
    state = StateFactory.create(api);

    wireRolePicker();
    wireNavigation();
    wireMobileMenu();
    wireInventoryFilters();
    wireDrawer();

    state.subscribe(onStateChange);

    // Show a neutral placeholder rather than a stale dash while the first
    // bootstrap request is in flight.
    setDateNote('Loading…');
    setRoleNote('Loading the server\u2019s view of this role…');

    // `loadBootstrap` emits 'bootstrap' on success, and `onStateChange` answers
    // that event with `setLoaded()`. So the first load is driven by the
    // subscription above, not from here — doing both would fetch the dashboard
    // and the briefing twice on every page load.
    state.loadBootstrap().catch(function (error) {
      setFailed(error);
    });
  }

  /* ------------------------------------------------------------- rendering */

  /**
   * The single place a fresh bootstrap payload is acted on.
   *
   * 'bootstrap' is emitted on every successful load — the first one included —
   * so `setLoaded` is only ever reached from here. Neither the startup path nor
   * the role picker calls it directly; doing so would load the dashboard and
   * the briefing twice, because both events fire on a role change.
   */
  function onStateChange(current, reason) {
    if (reason === 'bootstrap') {
      setLoaded();
    } else if (reason === 'role') {
      // The new role's permissions are not known until bootstrap answers, so
      // the picker is briefly inert again rather than showing stale rights.
      setPickerBusy(true);
      setRoleNote('Switching role…');
    } else if (reason === 'view') {
      renderView(current.view);
    } else if (reason === 'items') {
      // The one and only place the table is painted. State emits this after
      // every successful item request — the first load and every filter
      // change alike — so the two paths cannot drift apart.
      StockPulseInventory.render(current.items);
    } else if (reason === 'itemsLoading') {
      StockPulseInventory.renderLoading();
    } else if (reason === 'itemsError') {
      showItemsFailure(current.lastError);
    } else if (reason === 'dashboard') {
      // A refresh after a stock change. The briefing is not re-requested: the
      // cached one is re-rendered with the new figures, so the AI text stays
      // as it was until something asks for a new briefing.
      StockPulseDashboard.render(current.dashboard, current.briefing);
    } else if (reason === 'detailLoading') {
      openDrawerShell();
      StockPulseDrawer.renderLoading(current.detailId);
    } else if (reason === 'detail') {
      StockPulseDrawer.render(current.detail);
      // The sale controls act on the item just rendered, so they are bound to
      // it here rather than left pointing at whatever was shown before. When
      // this render is the refresh after a sale, the answer on screen is kept.
      StockPulseActions.bindItem(current.detail, current.canManage(), current.keepSaleNote);
    } else if (reason === 'detailError') {
      StockPulseDrawer.renderFailure(current.detailId, describeError(current.lastError, 'This item could not be loaded.'));
      StockPulseActions.reset();
    } else if (reason === 'detailClosed') {
      StockPulseActions.reset();
      closeDrawerShell();
    } else if (reason === 'saleBusy') {
      StockPulseActions.setBusy(true);
    } else if (reason === 'saleDone') {
      StockPulseActions.setBusy(false);
      StockPulseActions.showSaleResult(current.saleResult);
    } else if (reason === 'undoDone') {
      StockPulseActions.setBusy(false);
      StockPulseActions.showUndoResult(current.undoResult);
    } else if (reason === 'saleFailed') {
      StockPulseActions.setBusy(false);
      StockPulseActions.showFailure(current.lastError);
    } else if (reason === 'deliveryBusy') {
      StockPulseActions.setDeliveryBusy(true);
    } else if (reason === 'deliveryDone') {
      StockPulseActions.setDeliveryBusy(false);
      StockPulseActions.showDeliveryResult(current.deliveryResult);
    } else if (reason === 'deliveryFailed') {
      StockPulseActions.setDeliveryBusy(false);
      StockPulseActions.showDeliveryFailure(current.lastError);
    }
  }

  /** Applies everything the bootstrap payload authorises. */
  function setLoaded() {
    var data = state.bootstrap;
    if (!data) {
      return;
    }

    renderDate(data.today);
    renderRolePicker(data.role);
    renderRoleNote(data);
    setPickerBusy(false);
    setAddItemUnavailable();
    clearStatus();

    loadDashboard();
    loadItems();
  }

  /* --------------------------------------------------------------- dashboard */

  /**
   * Loads the dashboard and the advisor briefing, then hands both to the
   * renderer.
   *
   * The two requests are independent, so they run together. The dashboard is
   * required; the briefing is not — if it fails, the KPI strip and the queues
   * are still worth showing, so the failure is reported in the advisor card
   * instead of blanking the page.
   */
  function loadDashboard() {
    var dashboardPromise = state.loadDashboard();
    var briefingPromise = state.loadBriefing();

    dashboardPromise.then(function () {
      StockPulseDashboard.render(state.dashboard, state.briefing);
    }, function (error) {
      showDashboardFailure(error);
    });

    briefingPromise.then(function (briefing) {
      StockPulseDashboard.render(state.dashboard, briefing);
    }, function (error) {
      showBriefingFailure(error);
    });
  }

  function showDashboardFailure(error) {
    var message = (error && error.describe) ? error.describe() : 'The figures could not be loaded.';

    // Every KPI goes back to a dash: leaving a previous role's numbers on
    // screen would be worse than showing nothing.
    var figures = document.querySelectorAll('[data-figure]');
    for (var i = 0; i < figures.length; i++) {
      if (figures[i].classList.contains('kpi-value')) {
        figures[i].textContent = Format.DASH;
      }
    }

    paintEmpty('restockQueue', 'The restock queue could not be loaded.');
    paintEmpty('expiryQueue', 'The expiry watch could not be loaded.');
    setStatus(message);
  }

  function showBriefingFailure(error) {
    var message = (error && error.describe) ? error.describe() : 'The advisor could not be reached.';
    StockPulseDashboard.render(null, {
      source: 'RULES',
      unavailable: {
        message: message,
        remediation: 'The rule summary below is still written by StockPulse\u2019s own rules, not by AI.'
      }
    });
  }

  function paintEmpty(containerId, message) {
    var container = document.getElementById(containerId);
    if (container) {
      container.innerHTML = '<p class="empty-note">' + Format.escapeHtml(message) + '</p>';
    }
  }

  /* ---------------------------------------------------------------- inventory */

  /**
   * Loads the item list for the current filters.
   *
   * The filters themselves are applied by Java: this function only decides
   * which words to send. There is no filtering of rows after the response —
   * whatever Java returns is drawn, in the order it returned it.
   *
   * Neither the redraw nor the failure message happens here. `loadItems` only
   * starts the request; the 'items' / 'itemsLoading' / 'itemsError' events
   * that follow are what paint the table. A filter change made from the
   * toolbar therefore takes exactly the same route as the first load, and
   * there is no second painting path that could go stale.
   *
   * The rejection is swallowed because the event has already reported it;
   * letting it through as well would print the same failure twice.
   */
  function loadItems() {
    state.loadItems().catch(function () {
      // Reported by the 'itemsError' event handled in `onStateChange`.
    });
  }

  function showItemsFailure(error) {
    var message = (error && error.describe)
      ? error.describe()
      : 'The item list could not be loaded.';
    StockPulseInventory.renderFailure(message);
  }

  /**
   * The search box, the category box and the status pills.
   *
   * Search and category are debounced: typing "Dairy" should ask Java once,
   * not five times. The status pills are immediate — a click is a single
   * decision.
   *
   * Nothing is filtered here. Each handler only records the new filter value
   * and lets `state.setItemFilters` re-ask the server; the resulting 'items'
   * event repaints the table. The catch is empty for the same reason as in
   * `loadItems` — the failure is already reported by 'itemsError'.
   */
  function wireInventoryFilters() {
    var search = document.getElementById('invSearch');
    if (search) {
      var searchTimer = null;
      search.addEventListener('input', function () {
        if (searchTimer) {
          clearTimeout(searchTimer);
        }
        searchTimer = setTimeout(function () {
          searchTimer = null;
          state.setItemFilters({ search: search.value.trim() }).catch(ignoreItemsFailure);
        }, FILTER_DELAY_MS);
      });
    }

    var category = document.getElementById('invCategory');
    if (category) {
      var categoryTimer = null;
      category.addEventListener('input', function () {
        if (categoryTimer) {
          clearTimeout(categoryTimer);
        }
        categoryTimer = setTimeout(function () {
          categoryTimer = null;
          state.setItemFilters({ category: category.value.trim() }).catch(ignoreItemsFailure);
        }, FILTER_DELAY_MS);
      });
    }

    var pills = document.querySelectorAll('#invStatusFilters .filter-pill[data-status]');
    for (var i = 0; i < pills.length; i++) {
      pills[i].addEventListener('click', function (event) {
        var button = event.currentTarget;
        var status = button.getAttribute('data-status');
        setActivePill(button);
        state.setItemFilters({ status: status }).catch(ignoreItemsFailure);
      });
    }
  }

  /** The 'itemsError' event carries the message; this only stops the noise. */
  function ignoreItemsFailure() {
    // Deliberately empty.
  }

  /** Exactly one status pill is active at a time. */
  function setActivePill(activeButton) {
    var pills = document.querySelectorAll('#invStatusFilters .filter-pill[data-status]');
    for (var i = 0; i < pills.length; i++) {
      var isActive = pills[i] === activeButton;
      if (isActive) {
        pills[i].classList.add('active');
      } else {
        pills[i].classList.remove('active');
      }
      pills[i].setAttribute('aria-pressed', isActive ? 'true' : 'false');
    }
  }

  /* ------------------------------------------------------------------ drawer */

  /** The element that had focus before the drawer opened, restored on close. */
  var focusBeforeDrawer = null;

  /**
   * Wires the drawer: opening from a row, and the three ways to close it.
   *
   * Nothing here fetches anything. The close paths all call
   * `state.closeDetail()`, which discards the payload and emits
   * 'detailClosed'; the shell is hidden in response to that event, so the
   * drawer cannot be left open while the state says it is closed.
   */
  function wireDrawer() {
    StockPulseInventory.setSelectHandler(openItem);

    var submit = document.getElementById('saleSubmit');
    if (submit) {
      submit.addEventListener('click', submitSale);
    }

    var undo = document.getElementById('undoSaleBtn');
    if (undo) {
      undo.addEventListener('click', submitUndo);
    }

    var delivery = document.getElementById('deliverySubmit');
    if (delivery) {
      delivery.addEventListener('click', submitDelivery);
    }

    var close = document.getElementById('drawerClose');
    if (close) {
      close.addEventListener('click', requestClose);
    }

    var scrim = document.getElementById('drawerScrim');
    if (scrim) {
      scrim.addEventListener('click', requestClose);
    }

    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && isDrawerOpen()) {
        event.preventDefault();
        requestClose();
      }
    });
  }

  /** Opens the drawer for one item. Called with the id from the picked row. */
  function openItem(itemId) {
    focusBeforeDrawer = document.activeElement;

    // The shell is opened and the loading state drawn by the 'detailLoading'
    // event that `openDetail` emits, not here. Doing it in both places would
    // be two paths that could disagree about what the drawer is showing.
    state.openDetail(itemId).catch(function () {
      // Reported by the 'detailError' event handled in `onStateChange`.
    });
  }

  function requestClose() {
    state.closeDetail();
  }

  /**
   * Sends the sale form to Java.
   *
   * The two values are passed through as typed. No conversion, no rounding, no
   * check that the quantity is available - the server validates the number and
   * decides whether the stock covers it, and its refusal is shown as sent.
   */
  function submitSale() {
    var itemId = StockPulseActions.boundItemId();
    if (!itemId) {
      return;
    }

    var qtyField = document.getElementById('saleQty');
    var priceField = document.getElementById('salePrice');
    var qty = qtyField ? qtyField.value.trim() : '';
    var price = priceField ? priceField.value.trim() : '';

    if (qty === '') {
      // The one check this page makes: an empty box is not a number to send.
      StockPulseActions.showFailure({
        describe: function () { return 'Enter the quantity sold.'; }
      });
      return;
    }

    state.recordSale(itemId, qty, price).then(function () {
      if (qtyField) {
        qtyField.value = '';
      }
      if (priceField) {
        priceField.value = '';
      }
    }).catch(function () {
      // Reported by the 'saleFailed' event in `onStateChange`.
    });
  }

  /** Asks Java to undo the most recent sale of the item on screen. */
  function submitUndo() {
    var itemId = StockPulseActions.boundItemId();
    var button = document.getElementById('undoSaleBtn');
    // The button is hidden for a role that cannot undo; a hidden control must
    // not act even if it is reached another way.
    if (!itemId || (button && button.hidden)) {
      return;
    }
    state.undoSale(itemId).catch(function () {
      // Reported by the 'saleFailed' event in `onStateChange`.
    });
  }

  /**
   * Sends the delivery form to Java.
   *
   * Quantity, unit cost and the expiry date are passed through exactly as
   * typed, in the format the server reads (`dd/MM/yyyy`). This function does
   * not parse the date, check the cost, or decide whether an expiry is needed:
   * Java answers all three, and its refusal is shown as it was sent.
   *
   * The empty-box check is the same one the sale form makes — an empty field is
   * not a value to send — and it covers quantity and cost only. A blank expiry
   * is a real choice the server understands ("no expiry"), so it is passed on.
   */
  function submitDelivery() {
    var itemId = StockPulseActions.boundItemId();
    if (!itemId) {
      return;
    }

    var qtyField = document.getElementById('deliveryQty');
    var costField = document.getElementById('deliveryCost');
    var expiryField = document.getElementById('deliveryExpiry');

    var qty = qtyField ? qtyField.value.trim() : '';
    var cost = costField ? costField.value.trim() : '';
    var expiry = expiryField ? expiryField.value.trim() : '';

    if (qty === '') {
      StockPulseActions.showDeliveryFailure({
        describe: function () { return 'Enter the quantity received.'; }
      });
      return;
    }

    if (cost === '') {
      StockPulseActions.showDeliveryFailure({
        describe: function () { return 'Enter the unit cost.'; }
      });
      return;
    }

    state.receiveDelivery(itemId, qty, cost, expiry).then(function () {
      if (qtyField) {
        qtyField.value = '';
      }
      if (costField) {
        costField.value = '';
      }
      if (expiryField) {
        expiryField.value = '';
      }
    }).catch(function () {
      // Reported by the 'deliveryFailed' event in `onStateChange`.
    });
  }

  function isDrawerOpen() {
    var drawer = document.getElementById('itemDrawer');
    return Boolean(drawer) && !drawer.hidden;
  }

  function openDrawerShell() {
    var drawer = document.getElementById('itemDrawer');
    var scrim = document.getElementById('drawerScrim');
    if (drawer) {
      drawer.hidden = false;
    }
    if (scrim) {
      scrim.hidden = false;
    }
    // Focus moves into the drawer so a keyboard user is not left behind it.
    var close = document.getElementById('drawerClose');
    if (close) {
      close.focus();
    }
  }

  function closeDrawerShell() {
    var drawer = document.getElementById('itemDrawer');
    var scrim = document.getElementById('drawerScrim');
    if (drawer) {
      drawer.hidden = true;
    }
    if (scrim) {
      scrim.hidden = true;
    }
    // Return focus to whatever opened the drawer - normally the row itself.
    if (focusBeforeDrawer && typeof focusBeforeDrawer.focus === 'function') {
      focusBeforeDrawer.focus();
    }
    focusBeforeDrawer = null;
  }

  /** The Java message for a failure, or a plain fallback. */
  function describeError(error, fallback) {
    return (error && error.describe) ? error.describe() : fallback;
  }

  /**
   * The date the figures are for, exactly as Java wrote it.
   *
   * Java sends three fields: `text` ("26/11/2026"), `weekday` ("Thursday") and
   * `iso`. This page joins the two words Java produced and formats nothing
   * itself — there is no date parsing here, so a locale cannot drift between
   * the server and the screen.
   */
  function renderDate(today) {
    if (!today) {
      setDateNote('Date not supplied');
      return;
    }
    var weekday = today.weekday ? today.weekday : '';
    var text = Format.text(today.text);
    var label = weekday ? weekday + ' \u00B7 ' + text : text;

    setDateNote(label);
    var pill = document.getElementById('todayText');
    if (pill) {
      // The ISO date goes on the title so the exact day is unambiguous on
      // hover, again without this page formatting anything.
      pill.title = today.iso ? 'Server date: ' + today.iso : '';
    }
  }

  function setDateNote(label) {
    var element = document.getElementById('todayText');
    if (element) {
      element.textContent = label;
    }
  }

  /** Marks the picker button for the role the server says is in force. */
  function renderRolePicker(role) {
    var buttons = roleButtons();
    for (var i = 0; i < buttons.length; i++) {
      var button = buttons[i];
      var isActive = button.getAttribute('data-role') === role;
      button.classList.toggle('active', isActive);
      button.setAttribute('aria-pressed', isActive ? 'true' : 'false');
      button.disabled = false;
    }
  }

  /**
   * States what this role can do, using the server's own wording where it
   * matters. `canManage` and `writesBlocked` are the server's answers, not
   * this page's opinion of them.
   */
  function renderRoleNote(data) {
    var parts = [];
    parts.push(data.canManage
      ? 'This role may change stock, items and settings.'
      : 'This role may record sales, deliveries and waste, but not add or edit items.');

    if (data.writesBlocked) {
      parts.push('Saving is currently blocked because the last save failed, so every form is disabled.');
    }

    // The server's own sentence about what the picker is. Keeping it verbatim
    // means the page cannot drift into implying this is a login.
    if (data.notAuthentication) {
      parts.push(data.notAuthentication);
    }

    setRoleNote(parts.join(' '));
  }

  function setRoleNote(message) {
    var element = document.querySelector('.role-note');
    if (element) {
      element.textContent = message;
    }
  }

  function setPickerBusy(busy) {
    var buttons = roleButtons();
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].disabled = busy;
      if (busy) {
        buttons[i].setAttribute('aria-busy', 'true');
      } else {
        buttons[i].removeAttribute('aria-busy');
      }
    }
  }

  function roleButtons() {
    return document.querySelectorAll('.role-option[data-role]');
  }

  /**
   * The Add Item button stays disabled for both roles.
   *
   * The add-item form is built in a later step. Until then the button has no
   * click handler, so enabling it for a Manager would be a button that looks
   * live and does nothing — the exact lie the shell's CSS warns against.
   *
   * `canManage` is still read, because it is the reason the button will be
   * enabled later; here it only chooses which honest explanation to show. A
   * Manager is told the feature is coming, Staff is told it is not theirs.
   */
  function setAddItemUnavailable() {
    var button = document.getElementById('addItemBtn');
    if (!button) {
      return;
    }
    button.disabled = true;
    if (state.writesBlocked()) {
      button.title = 'Saving is blocked until the data files are recovered';
    } else if (state.canManage()) {
      button.title = 'Available in a later step';
    } else {
      button.title = 'Manager only \u2014 available in a later step';
    }
  }

  /* ------------------------------------------------------------------ views */

  function renderView(view) {
    var sections = document.querySelectorAll('.view-section');
    for (var i = 0; i < sections.length; i++) {
      var section = sections[i];
      section.classList.toggle('active', section.id === 'view-' + view);
    }

    var buttons = document.querySelectorAll('.nav-btn[data-view]');
    for (var j = 0; j < buttons.length; j++) {
      var button = buttons[j];
      var isActive = button.getAttribute('data-view') === view;
      button.classList.toggle('active', isActive);
      if (isActive) {
        button.setAttribute('aria-current', 'page');
      } else {
        button.removeAttribute('aria-current');
      }
    }

    var copy = VIEW_TEXT[view];
    if (copy) {
      setText('viewTitle', copy.title);
      setText('viewSubtitle', copy.subtitle);
    }

    closeMobileMenu();
  }

  function wireNavigation() {
    var buttons = document.querySelectorAll('.nav-btn[data-view]');
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].addEventListener('click', function (event) {
        var view = event.currentTarget.getAttribute('data-view');
        state.setView(view);
      });
    }
  }

  /* ------------------------------------------------------------ role picker */

  function wireRolePicker() {
    var buttons = roleButtons();
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].addEventListener('click', function (event) {
        var role = event.currentTarget.getAttribute('data-role');
        if (role === state.role && state.bootstrap) {
          return;
        }
        setPickerBusy(true);
        setRoleNote('Switching role\u2026');
        // `setRole` reloads bootstrap and emits 'bootstrap', which is what
        // calls `setLoaded()`. Calling it here as well would load the
        // dashboard twice.
        state.setRole(role).catch(function (error) {
          setFailed(error);
        });
      });
    }
  }

  /* ------------------------------------------------------------ mobile menu */

  function wireMobileMenu() {
    var button = document.getElementById('mobileMenuBtn');
    if (!button) {
      return;
    }
    button.disabled = false;
    button.addEventListener('click', function () {
      var shell = document.getElementById('appShell');
      if (!shell) {
        return;
      }
      var open = shell.classList.toggle('sidebar-open');
      button.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
  }

  function closeMobileMenu() {
    var shell = document.getElementById('appShell');
    if (shell) {
      shell.classList.remove('sidebar-open');
    }
    var button = document.getElementById('mobileMenuBtn');
    if (button) {
      button.setAttribute('aria-expanded', 'false');
    }
  }

  /* ----------------------------------------------------------------- status */

  /**
   * A visible statement when startup failed. Without this the page would show
   * dashes and no explanation, which reads as "there is no stock" rather than
   * "the server is not answering".
   */
  function setFailed(error) {
    setPickerBusy(false);
    var message = (error && error.describe) ? error.describe() : 'The server did not answer.';
    setDateNote('No date');
    setRoleNote('The page could not reach the server, so no role is in force. ' + message);
    setStatus(message);
  }

  function setStatus(message) {
    var region = ensureStatusRegion();
    region.textContent = message;
    region.hidden = false;
  }

  function clearStatus() {
    var region = document.getElementById('statusRegion');
    if (region) {
      region.hidden = true;
      region.textContent = '';
    }
  }

  /**
   * The status strip is created here rather than in the shell because it only
   * exists to report a failure; the static page has nothing to say.
   */
  function ensureStatusRegion() {
    var region = document.getElementById('statusRegion');
    if (region) {
      return region;
    }
    region = document.createElement('div');
    region.id = 'statusRegion';
    region.className = 'status-strip is-danger';
    region.setAttribute('role', 'status');

    var main = document.querySelector('.main-area');
    var topBar = document.querySelector('.top-bar');
    if (main && topBar && topBar.parentNode === main) {
      main.insertBefore(region, topBar.nextSibling);
    } else if (main) {
      main.appendChild(region);
    }
    return region;
  }

  function setText(id, value) {
    var element = document.getElementById(id);
    if (element) {
      element.textContent = value;
    }
  }

  /* ------------------------------------------------------------------- start */

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
}(window));
