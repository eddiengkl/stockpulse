/*
 * StockPulse — inventory.js
 *
 * Renders the Inventory table from GET /api/items.
 *
 * The rule this file keeps, and the reason it is a separate file:
 *
 *   Every cell is a value Java sent, or a Java-provided sentence that already
 *   contains the value. Nothing is filtered, sorted, summed, rounded or
 *   re-derived here — the filters live in `state.js` and are answered by Java,
 *   and the row order is whatever order the response arrived in.
 *
 * Two fields drive the presentation only:
 *
 *   itemType   PERISHABLE / LONG_LIFE — printed as a readable label
 *   stockoutRisk  a boolean Java already decided; it picks the reorder chip,
 *                 and it never decides whether the row is shown (the server
 *                 filtered). It answers one narrow question — "below the
 *                 reorder point?" — so the chip is labelled for that alone and
 *                 is not presented as an overall health verdict.
 *
 * The most delicate columns carry their own text. `stockDescription` is
 * Java's sentence about the stock ("24 L in 2 batches, oldest 5 days left"),
 * `daysOfCoverText` is Java's phrasing of the cover ("5.51 days" or "never
 * runs out (no sales)"), and `discountText` is Java's discount wording. Those
 * are printed verbatim so the page cannot disagree with the server about a
 * number it does not own.
 *
 * Where a field arrives as a bare number with no sentence — `daysUntilExpiry`,
 * `reorderPoint`, `reorderQuantity`, `stockOnHand` — this file attaches only a
 * unit label and prints the number untouched. It adds no judgement of its own:
 * a negative day count is shown as a negative number, not as the word
 * "Expired", because deciding what a number means is Java's job.
 */

(function (global) {
  'use strict';

  var Format = global.StockPulseFormat;

  /* --------------------------------------------------------------- helpers */

  /**
   * A quantity, written the way Java wrote it.
   *
   * `stockDescription` already reads "24 L in 2 batches, …", so the unit is in
   * the sentence. The bare `stockOnHand` figure is printed with the unit
   * appended here only because the cell shows a number on its own; the number
   * itself is untouched.
   */
  function quantityWithUnit(value, unit) {
    if (value === null || value === undefined || value === '') {
      return Format.DASH;
    }
    return Format.count(value) + (unit ? ' ' + Format.escapeHtml(unit) : '');
  }

  /** PERISHABLE -> Perishable, LONG_LIFE -> Long life. Presentation only. */
  function typeLabel(itemType) {
    var raw = String(itemType || '');
    if (raw === 'LONG_LIFE') {
      return 'Long life';
    }
    return Format.titleCase(raw.replace(/_/g, ' '));
  }

  /**
   * The expiry cell.
   *
   * Java supplies two things and no phrase: `noExpiry`, a flag, and
   * `daysUntilExpiry`, a signed number. This cell therefore does exactly two
   * things — it lets `noExpiry` choose the no-expiry wording, and otherwise it
   * prints the number Java sent, sign included, with the fixed unit "d".
   *
   * It deliberately does NOT decide that a negative number means expired. That
   * is a reading of the value, and reading values is Java's job; the same
   * item is already flagged on the dashboard by Java's own rules. Writing
   * "Expired" here would be the page inventing a verdict from a number.
   */
  function expiryCell(item) {
    if (item.noExpiry) {
      return '<span class="cell-muted">No expiry</span>';
    }
    return Format.escapeHtml(Format.daysSigned(item.daysUntilExpiry));
  }

  /* ------------------------------------------------------------------ render */

  /**
   * Called with an item id when a row is chosen. Set by app.js.
   *
   * The table does not know what happens next: it reports which row was
   * picked and lets the caller open the drawer. That keeps this file free of
   * navigation and request logic.
   */
  var onSelect = null;

  /** Registers the row-selection handler. */
  function setSelectHandler(handler) {
    onSelect = handler;
  }

  /**
   * Draws the table.
   *
   * @param {object} payload the `data` of GET /api/items
   */
  function render(payload) {
    var body = document.getElementById('invTableBody');
    if (!body) {
      return;
    }

    var items = (payload && payload.items) || [];
    renderCountNote(payload, items.length);

    if (items.length === 0) {
      body.innerHTML = ''
        + '<tr class="placeholder-row"><td colspan="11">'
        + Format.escapeHtml(emptyMessage(payload))
        + '</td></tr>';
      return;
    }

    var html = '';
    for (var i = 0; i < items.length; i++) {
      html += row(items[i]);
    }
    body.innerHTML = html;
    wireRows(body);
  }

  /**
   * Makes each row open the drawer, by mouse or by keyboard.
   *
   * The listener is attached once per painted body, not by inline onclick
   * attributes, so no API value is ever interpolated into an attribute that
   * JavaScript then evaluates. The id is read back from the row's data
   * attribute, which was escaped when the row was written.
   */
  function wireRows(body) {
    var rows = body.querySelectorAll('tr[data-item-id]');
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      // A row that opens a drawer is a control, so it says so and can be
      // reached by Tab rather than being mouse-only.
      row.classList.add('is-selectable');
      row.setAttribute('role', 'button');
      row.setAttribute('tabindex', '0');
      row.setAttribute('aria-label', 'Open ' + row.getAttribute('data-item-id') + ' details');

      row.addEventListener('click', handleRowActivate);
      row.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' || event.key === ' ') {
          // Space would otherwise scroll the page.
          event.preventDefault();
          handleRowActivate(event);
        }
      });
    }
  }

  function handleRowActivate(event) {
    if (!onSelect) {
      return;
    }
    var row = event.currentTarget;
    var itemId = row.getAttribute('data-item-id');
    if (itemId) {
      onSelect(itemId);
    }
  }

  /**
   * The "3 of 6" line under the toolbar.
   *
   * Both numbers come from Java. When no filter is active the two are equal
   * and the line simply states the total.
   */
  function renderCountNote(payload, shown) {
    var note = document.getElementById('invCountNote');
    if (!note) {
      return;
    }

    var count = payload ? payload.count : shown;
    var total = payload ? payload.totalBeforeFilter : null;

    if (total === null || total === undefined || count === total) {
      note.textContent = count === 1 ? '1 item' : Format.count(count) + ' items';
      return;
    }
    note.textContent = 'Showing ' + Format.count(count) + ' of ' + Format.count(total) + ' items';
  }

  /** What to say when Java returned nothing, without guessing why. */
  function emptyMessage(payload) {
    var total = payload ? payload.totalBeforeFilter : 0;
    if (total === 0) {
      return 'There are no items in the ledger yet.';
    }
    return 'No items match these filters.';
  }

  /**
   * The reorder chip.
   *
   * It reports one thing only: Java's `stockoutRisk` boolean, which answers
   * "is this item below its reorder point?". It is NOT a verdict on the item's
   * overall health, and the two labels are worded so they cannot be read as
   * one. An item can carry an already-passed expiry date and still have
   * `stockoutRisk: false`, because Java measures reorder risk against sellable
   * stock and a passed date is a separate concern. A chip reading "OK" in that
   * situation would claim the item is fine; "No reorder alert" says only what
   * the boolean actually says.
   *
   * The false state is styled neutrally rather than green, for the same
   * reason: green is this page's success colour, and "not below the reorder
   * point" is an absence of one alert, not a statement of success.
   */
  function row(item) {
    var riskChip = item.stockoutRisk
      ? '<span class="row-chip is-danger">Below reorder</span>'
      : '<span class="row-chip is-neutral">No reorder alert</span>';

    return ''
      + '<tr data-item-id="' + Format.escapeHtml(item.itemId) + '">'
      +   '<td>'
      +     '<div class="cell-name">' + Format.escapeHtml(item.name) + '</div>'
      +     '<div class="cell-meta">' + Format.escapeHtml(item.itemId)
      +       (item.supplierName ? ' · ' + Format.escapeHtml(item.supplierName) : '')
      +     '</div>'
      +   '</td>'
      +   '<td>' + Format.escapeHtml(Format.text(item.category)) + '</td>'
      +   '<td>'
      +     '<div>' + Format.escapeHtml(typeLabel(item.itemType)) + '</div>'
      +     '<div class="cell-meta">' + riskChip + '</div>'
      +   '</td>'
      +   '<td>'
      +     '<div>' + quantityWithUnit(item.stockOnHand, item.unit) + '</div>'
      +     '<div class="cell-meta">' + Format.escapeHtml(Format.text(item.stockDescription)) + '</div>'
      +   '</td>'
      +   '<td>' + Format.escapeHtml(Format.text(item.daysOfCoverText)) + '</td>'
      +   '<td>' + quantityWithUnit(item.reorderPoint, item.unit) + '</td>'
      +   '<td>' + quantityWithUnit(item.reorderQuantity, item.unit) + '</td>'
      +   '<td>' + expiryCell(item) + '</td>'
      +   '<td>' + discountCell(item) + '</td>'
      +   '<td>' + Format.money(item.stockValueAtCost) + '</td>'
      +   '<td>' + Format.money(item.stockValueAtPrice) + '</td>'
      +   '</tr>';
  }

  /** Java's discount wording. "not configured" is a fact, not an error. */
  function discountCell(item) {
    var text = Format.text(item.discountText);
    if (text === Format.DASH) {
      return '<span class="cell-muted">' + Format.DASH + '</span>';
    }
    var muted = String(item.discountText).toLowerCase() === 'not configured';
    return '<span class="' + (muted ? 'cell-muted' : '') + '">'
      + Format.escapeHtml(text) + '</span>';
  }

  /** Shown while a request is in flight. */
  function renderLoading() {
    var body = document.getElementById('invTableBody');
    if (body) {
      body.innerHTML = '<tr class="placeholder-row"><td colspan="11">Loading items…</td></tr>';
    }
  }

  /** Shown when the list could not be fetched. Says nothing about the data. */
  function renderFailure(message) {
    var body = document.getElementById('invTableBody');
    if (body) {
      body.innerHTML = '<tr class="placeholder-row"><td colspan="11">'
        + Format.escapeHtml(message) + '</td></tr>';
    }
    var note = document.getElementById('invCountNote');
    if (note) {
      note.textContent = '';
    }
  }

  global.StockPulseInventory = {
    render: render,
    renderLoading: renderLoading,
    renderFailure: renderFailure,
    setSelectHandler: setSelectHandler
  };
}(window));
