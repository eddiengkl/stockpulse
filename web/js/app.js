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
    wireItemModal();

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
    } else if (reason === 'wasteBusy') {
      StockPulseActions.setWasteBusy(true);
    } else if (reason === 'wasteDone') {
      StockPulseActions.setWasteBusy(false);
      StockPulseActions.showWasteResult(current.wasteResult);
    } else if (reason === 'wasteFailed') {
      StockPulseActions.setWasteBusy(false);
      StockPulseActions.showWasteFailure(current.lastError);
    } else if (reason === 'addItemBusy') {
      setItemSaving(true);
    } else if (reason === 'addItemDone') {
      setItemSaving(false);
      onItemAdded(current.addItemResult);
    } else if (reason === 'addItemFailed') {
      setItemSaving(false);
      // The modal stays open with what was typed still in it, so a wrong price
      // can be corrected without re-entering the rest.
      setItemNote(describeError(current.lastError, 'The item could not be added.'), 'error');
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
    setAddItemAvailability();
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

    var waste = document.getElementById('wasteSubmit');
    if (waste) {
      waste.addEventListener('click', submitWaste);
    }

    var wasteBatch = document.getElementById('wasteBatch');
    if (wasteBatch) {
      wasteBatch.addEventListener('change', function () {
        // The picker's hint follows the chosen batch. `state.detail` is the
        // payload the drawer is currently bound to, so the figures in the hint
        // are the ones Java sent for this item.
        if (state.detail) {
          StockPulseActions.updateWasteBatchHint(state.detail);
        }
      });
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

  /**
   * Sends the waste form to Java.
   *
   * The batch, quantity and reason are passed through as chosen and typed. This
   * function does not check that the batch belongs to the item, that it has
   * that much left, or that the reason is acceptable - Java does all three, and
   * its refusal is shown as sent. The only local check is that a batch is
   * chosen and the quantity box is not empty, since an empty box is not a
   * number to send.
   */
  function submitWaste() {
    var itemId = StockPulseActions.boundItemId();
    if (!itemId) {
      return;
    }

    var select = document.getElementById('wasteBatch');
    var qtyField = document.getElementById('wasteQty');
    var reasonField = document.getElementById('wasteReason');

    var batchId = select ? select.value : '';
    var qty = qtyField ? qtyField.value.trim() : '';
    var reason = reasonField ? reasonField.value.trim() : '';

    if (!batchId) {
      StockPulseActions.showWasteFailure({
        describe: function () { return 'Choose the batch the waste came from.'; }
      });
      return;
    }

    if (qty === '') {
      StockPulseActions.showWasteFailure({
        describe: function () { return 'Enter the quantity wasted.'; }
      });
      return;
    }

    state.recordWaste(itemId, batchId, qty, reason).then(function () {
      if (qtyField) {
        qtyField.value = '';
      }
      if (reasonField) {
        reasonField.value = '';
      }
    }).catch(function () {
      // Reported by the 'wasteFailed' event in `onStateChange`.
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

  /* ------------------------------------------------------------- add item */

  /**
   * The fields of the add-item form, in the names Java reads.
   *
   * Each entry maps a DOM id to its request field. The list is the whole
   * contract of POST /api/items: `itemId`, `itemType`, `name`, `category`,
   * `unit`, `sellingPrice`, `unitCost`, `shelfLifeDays`, `packSize`,
   * `supplierName`, `leadTimeDays`, plus the optional `season` and
   * `seasonalMultiplier`.
   *
   * `required` here means "this box must not be empty before the request is
   * sent", which is the same courtesy the sale and delivery forms make. It is
   * not a validation rule: whether a value is acceptable is Java's call, and a
   * numeric field that is present but wrong is still sent to be refused.
   */
  var ADD_ITEM_FIELDS = [
    { id: 'fItemId', field: 'itemId', numeric: false, required: true },
    { id: 'fItemType', field: 'itemType', numeric: false, required: true },
    { id: 'fName', field: 'name', numeric: false, required: true },
    { id: 'fCategory', field: 'category', numeric: false, required: true },
    { id: 'fUnit', field: 'unit', numeric: false, required: true },
    { id: 'fSellingPrice', field: 'sellingPrice', numeric: true, required: true },
    { id: 'fUnitCost', field: 'unitCost', numeric: true, required: true },
    { id: 'fShelfLife', field: 'shelfLifeDays', numeric: true, required: true },
    { id: 'fPackSize', field: 'packSize', numeric: true, required: true },
    { id: 'fSupplier', field: 'supplierName', numeric: false, required: true },
    { id: 'fLeadTime', field: 'leadTimeDays', numeric: true, required: true },
    { id: 'fSeason', field: 'season', numeric: false, required: false },
    { id: 'fMultiplier', field: 'seasonalMultiplier', numeric: true, required: false }
  ];

  /** Human names for the fields, used only when the browser catches a blank box. */
  var FIELD_LABELS = {
    itemId: 'an item id',
    itemType: 'an item type',
    name: 'a name',
    category: 'a category',
    unit: 'a unit',
    sellingPrice: 'a selling price',
    unitCost: 'a unit cost',
    shelfLifeDays: 'a shelf life in days',
    packSize: 'a pack size',
    supplierName: 'a supplier',
    leadTimeDays: 'a lead time in days'
  };

  function wireItemModal() {
    var open = document.getElementById('addItemBtn');
    if (open) {
      open.addEventListener('click', openItemModal);
    }

    var close = document.getElementById('itemModalClose');
    if (close) {
      close.addEventListener('click', closeItemModal);
    }

    var scrim = document.getElementById('itemModalScrim');
    if (scrim) {
      scrim.addEventListener('click', closeItemModal);
    }

    var save = document.getElementById('itemModalSave');
    if (save) {
      save.addEventListener('click', submitItem);
    }

    // The season picker only matters when a season is chosen, so the
    // multiplier is disabled while "None" is selected. Java still validates
    // both, and still accepts a blank season.
    var season = document.getElementById('fSeason');
    if (season) {
      season.addEventListener('change', syncSeasonFields);
    }

    document.addEventListener('keydown', function (event) {
      if ((event.key === 'Escape' || event.key === 'Esc') && isItemModalOpen()) {
        closeItemModal();
      }
    });
  }

  function isItemModalOpen() {
    var modal = document.getElementById('itemModal');
    return Boolean(modal) && !modal.hidden;
  }

  /** Clears every field of the add form back to its placeholder state. */
  function resetItemForm() {
    for (var i = 0; i < ADD_ITEM_FIELDS.length; i++) {
      var field = document.getElementById(ADD_ITEM_FIELDS[i].id);
      if (!field) {
        continue;
      }
      if (field.tagName === 'SELECT') {
        field.selectedIndex = 0;
      } else {
        field.value = '';
      }
    }
    // A new item is perishable until told otherwise, which is the common case
    // in this catalogue and the type whose form asks for a shelf life.
    var type = document.getElementById('fItemType');
    if (type) {
      type.value = 'PERISHABLE';
    }
    setItemNote('Every rule is checked in Java. If a value is refused, the reason it gives is shown here.', '');
    syncSeasonFields();
  }

  /** Fills the season picker from Java's bootstrap list. */
  function fillSeasonOptions() {
    var select = document.getElementById('fSeason');
    if (!select || !state.bootstrap) {
      return;
    }
    var seasons = state.bootstrap.seasons || [];
    select.innerHTML = '';
    var none = document.createElement('option');
    none.value = '';
    none.textContent = 'None';
    select.appendChild(none);
    for (var i = 0; i < seasons.length; i++) {
      var option = document.createElement('option');
      option.value = seasons[i].name;
      option.textContent = seasons[i].name + ' (' + seasons[i].monthRange + ')';
      select.appendChild(option);
    }
    select.selectedIndex = 0;
  }

  /** Disables the multiplier until a season is chosen. */
  function syncSeasonFields() {
    var season = document.getElementById('fSeason');
    var multiplier = document.getElementById('fMultiplier');
    if (!season || !multiplier) {
      return;
    }
    var hasSeason = season.value !== '';
    multiplier.disabled = !hasSeason;
    if (!hasSeason) {
      multiplier.value = '';
    }
  }

  /** Shows a message in the modal footer. `tone` is '', 'ok' or 'error'. */
  function setItemNote(text, tone) {
    var note = document.getElementById('itemModalNote');
    if (!note) {
      return;
    }
    note.textContent = text || '';
    note.className = 'modal-note' + (tone ? ' is-' + tone : '');
  }

  function openItemModal() {
    // A disabled control cannot fire this, but guard anyway: the modal is a
    // Manager tool and must not open for a role Java said cannot manage.
    if (!state.canManage() || state.writesBlocked()) {
      return;
    }
    var modal = document.getElementById('itemModal');
    var scrim = document.getElementById('itemModalScrim');
    if (!modal) {
      return;
    }
    resetItemForm();
    fillSeasonOptions();
    modal.hidden = false;
    if (scrim) {
      scrim.hidden = false;
    }
    var first = document.getElementById('fItemId');
    if (first) {
      first.focus();
    }
  }

  function closeItemModal() {
    var modal = document.getElementById('itemModal');
    var scrim = document.getElementById('itemModalScrim');
    if (modal) {
      modal.hidden = true;
    }
    if (scrim) {
      scrim.hidden = true;
    }
    var open = document.getElementById('addItemBtn');
    if (open) {
      open.focus();
    }
  }

  /**
   * Sends the add-item form to Java.
   *
   * The only checks made here are that a required box is not empty. Every
   * rule - is the id free, is the price non-negative, is the pack size at
   * least 1, does the season exist - is Java's, and its refusal is shown in
   * the footer exactly as it was worded.
   */
  function submitItem() {
    if (!state.canManage() || state.writesBlocked()) {
      return;
    }

    var fields = {};
    for (var i = 0; i < ADD_ITEM_FIELDS.length; i++) {
      var spec = ADD_ITEM_FIELDS[i];
      var element = document.getElementById(spec.id);
      var value = element ? element.value.trim() : '';

      if (value === '') {
        if (spec.required) {
          setItemNote('Enter ' + (FIELD_LABELS[spec.field] || spec.field) + '.', 'error');
          if (element) {
            element.focus();
          }
          return;
        }
        // A blank optional field is simply not sent, so Java falls back to its
        // own default instead of receiving an empty string.
        continue;
      }

      fields[spec.field] = value;
    }

    // The multiplier is only meaningful with a season; sending a leftover
    // number with no season would be a value Java never asked for.
    if (!fields.season) {
      delete fields.seasonalMultiplier;
    }

    state.addItem(fields).catch(function () {
      // Reported by the 'addItemFailed' event in `onStateChange`.
    });
  }

  /** Disables the save and close controls while the request is in flight. */
  function setItemSaving(saving) {
    var save = document.getElementById('itemModalSave');
    var close = document.getElementById('itemModalClose');
    if (save) {
      save.disabled = saving;
      save.textContent = saving ? 'Saving…' : 'Save item';
    }
    if (close) {
      close.disabled = saving;
    }
  }

  /**
   * Closes the modal and says what Java added.
   *
   * The list and dashboard refresh is started by the state layer, best effort:
   * the item is already saved by the time this runs, so a slow or failed refresh
   * must not turn a successful add into a reported failure. The message is
   * Java's own sentence, naming the item and its new id.
   */
  function onItemAdded(result) {
    closeItemModal();
    if (result && result.message) {
      setStatus(Format.text(result.message));
    }
  }

  /**
   * The Add Item button: Manager only, and never while saving is blocked.
   *
   * `canManage` comes from the bootstrap Java answered for the current role, so
   * the button follows the server's view rather than the picker's. Staff see it
   * disabled with an explanation instead of a control that can only be refused;
   * Java still enforces the rule if this is ever wrong.
   */
  function setAddItemAvailability() {
    var button = document.getElementById('addItemBtn');
    if (!button) {
      return;
    }
    if (state.writesBlocked()) {
      button.disabled = true;
      button.title = 'Saving is blocked until the data files are recovered';
    } else if (state.canManage()) {
      button.disabled = false;
      button.title = 'Add a new item to the catalogue';
    } else {
      button.disabled = true;
      button.title = 'Manager only';
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
