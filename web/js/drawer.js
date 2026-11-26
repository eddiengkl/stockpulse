/*
 * StockPulse — drawer.js
 *
 * Renders the item detail drawer from GET /api/items/{id}.
 *
 * The rule this file keeps:
 *
 *   Every value shown is a field Java sent, printed as Java sent it. Nothing
 *   is sorted, totalled, formatted, converted or inferred here.
 *
 * Three things in this endpoint already arrive finished, and are printed
 * without being touched:
 *
 *   batches[].receivedDate / expiryDate   already dd/MM/uuuu, already in
 *                                         Java's order - not re-ordered, and
 *                                         never re-parsed into a Date
 *   recentSales[].date                    same
 *   recentSales[].batchDraws              a String such as "B0001:5", not a
 *                                         list - printed verbatim, not parsed
 *   breakdown.*Formula                    Java's own working, printed as text
 *
 * Where Java sends a bare number with no sentence (quantities, money, days
 * left, a cost), only a unit or currency label is attached. The number itself
 * is never recomputed. A negative day count is shown as a negative number:
 * deciding what it means is Java's job, not this file's. Day counts use one
 * fixed unit, "d", in both directions - this file never chooses between
 * "day" and "days".
 *
 * All API text passes through Format.escapeHtml before reaching innerHTML, so
 * an item name or supplier containing markup renders as text.
 */

(function (global) {
  'use strict';

  var Format = global.StockPulseFormat;

  /* --------------------------------------------------------------- helpers */

  /** Java's own date string. Returned untouched, or a dash when absent. */
  function javaDate(value) {
    return Format.escapeHtml(Format.text(value));
  }

  /** A quantity with its unit. The number is Java's; only the label is added. */
  function withUnit(value, unit) {
    if (value === null || value === undefined || value === '') {
      return Format.DASH;
    }
    return Format.escapeHtml(Format.count(value)) + (unit ? ' ' + Format.escapeHtml(unit) : '');
  }

  /** A signed day count with the fixed unit used across this page. */
  function daysSigned(value) {
    return Format.escapeHtml(Format.daysSigned(value));
  }

  /** A non-negative day count, same fixed unit. Used for Java's lead time. */
  function daysNumber(value) {
    return Format.escapeHtml(Format.daysNumber(value));
  }

  /** One "label / value" pair in the key-facts grid. */
  function fact(label, valueHtml) {
    return ''
      + '<div class="fact">'
      +   '<div class="fact-label">' + Format.escapeHtml(label) + '</div>'
      +   '<div class="fact-value">' + valueHtml + '</div>'
      + '</div>';
  }

  /* -------------------------------------------------------------- rendering */

  /**
   * Draws the whole drawer for one item.
   *
   * @param {object} item the `data` of GET /api/items/{id}
   */
  function render(item) {
    if (!item) {
      return;
    }

    setText('drawerTitle', item.name);
    setText('drawerSubtitle', item.itemId
      + (item.supplierName ? ' \u00B7 ' + item.supplierName : ''));

    renderFacts(item);
    renderStock(item);
    renderBatches(item);
    renderSales(item);
    renderBreakdown(item);
  }

  /** The identity and pricing block at the top of the body. */
  function renderFacts(item) {
    var container = document.getElementById('drawerFacts');
    if (!container) {
      return;
    }

    var typeLabel = item.itemType === 'LONG_LIFE'
      ? 'Long life'
      : Format.titleCase(String(item.itemType || '').replace(/_/g, ' '));

    container.innerHTML = ''
      + fact('Category', Format.escapeHtml(Format.text(item.category)))
      + fact('Type', Format.escapeHtml(typeLabel))
      + fact('Supplier', Format.escapeHtml(Format.text(item.supplierName)))
      + fact('Lead time', daysNumber(item.leadTimeDays))
      + fact('Pack size', Format.escapeHtml(Format.count(item.packSize)))
      + fact('Unit cost', Format.money(item.unitCost))
      + fact('Selling price', Format.money(item.sellingPrice))
      + fact('Discount', discountHtml(item));
  }

  /** Java's discount wording. "not configured" is a fact, not an error. */
  function discountHtml(item) {
    var text = Format.text(item.discountText);
    if (text === Format.DASH) {
      return '<span class="fact-muted">' + Format.DASH + '</span>';
    }
    var muted = String(item.discountText).toLowerCase() === 'not configured';
    return '<span class="' + (muted ? 'fact-muted' : '') + '">'
      + Format.escapeHtml(text) + '</span>';
  }

  /**
   * The stock figures.
   *
   * `stockDescription` and `daysOfCoverText` are Java's sentences and are
   * printed as they stand. `stockValueFormula` is Java's own working for the
   * value, shown so the figure can be checked rather than trusted.
   */
  function renderStock(item) {
    var container = document.getElementById('drawerStock');
    if (!container) {
      return;
    }

    container.innerHTML = ''
      + '<div class="drawer-callout">'
      +   Format.escapeHtml(Format.text(item.stockDescription))
      + '</div>'
      + '<div class="fact-grid">'
      +   fact('On hand', withUnit(item.stockOnHand, item.unit))
      +   fact('Sellable', withUnit(item.sellableStock, item.unit))
      +   fact('Batches', Format.escapeHtml(Format.count(item.batchCount)))
      +   fact('Cover', Format.escapeHtml(Format.text(item.daysOfCoverText)))
      +   fact('Reorder at', withUnit(item.reorderPoint, item.unit))
      +   fact('Order qty', withUnit(item.reorderQuantity, item.unit))
      +   fact('Expiry', expiryHtml(item))
      +   fact('Value at cost', Format.money(item.stockValueAtCost))
      +   fact('Value at price', Format.money(item.stockValueAtPrice))
      + '</div>'
      + '<p class="drawer-formula">' + Format.escapeHtml(Format.text(item.stockValueFormula)) + '</p>';
  }

  /**
   * The expiry figure, using the same wording as the Inventory table.
   *
   * Java's `noExpiry` flag chooses the no-expiry wording; otherwise the signed
   * number is printed as sent. No reading of the number happens here.
   */
  function expiryHtml(item) {
    if (item.noExpiry) {
      return '<span class="fact-muted">No expiry</span>';
    }
    return daysSigned(item.daysUntilExpiry);
  }

  /**
   * The batch list, in the order Java returned it.
   *
   * Dates are Java's strings. `expired`, `depleted` and `sellable` are Java's
   * booleans and only choose a chip's wording, never a colour decision this
   * page made up.
   */
  function renderBatches(item) {
    var container = document.getElementById('drawerBatches');
    if (!container) {
      return;
    }

    var batches = item.batches || [];
    if (batches.length === 0) {
      container.innerHTML = '<p class="empty-note">This item has no batches recorded.</p>';
      return;
    }

    var html = ''
      + '<table class="drawer-table">'
      +   '<thead><tr>'
      +     '<th scope="col">Batch</th>'
      +     '<th scope="col">Remaining</th>'
      +     '<th scope="col">Received</th>'
      +     '<th scope="col">Expiry</th>'
      +     '<th scope="col">Days left (d)</th>'
      +     '<th scope="col">State</th>'
      +   '</tr></thead>'
      +   '<tbody>';

    for (var i = 0; i < batches.length; i++) {
      var b = batches[i];
      html += ''
        + '<tr data-batch-id="' + Format.escapeHtml(b.batchId) + '">'
        +   '<td>' + Format.escapeHtml(b.batchId) + '</td>'
        +   '<td>' + withUnit(b.qtyRemaining, b.unit) + '</td>'
        +   '<td>' + javaDate(b.receivedDate) + '</td>'
        +   '<td>' + javaDate(b.expiryDate) + '</td>'
        +   '<td>' + batchDaysLeft(b) + '</td>'
        +   '<td>' + batchStateChips(b) + '</td>'
        + '</tr>';
    }

    html += '</tbody></table>';
    container.innerHTML = html;
  }

  /** Java's `daysLeft` number, or a dash when there is no expiry date. */
  function batchDaysLeft(batch) {
    if (batch.daysLeft === null || batch.daysLeft === undefined) {
      return '<span class="fact-muted">' + Format.DASH + '</span>';
    }
    return daysSigned(batch.daysLeft);
  }

  /**
   * The state chips for one batch.
   *
   * Each chip is driven by one Java boolean. A sellable batch says nothing
   * rather than claiming health; the two exception states are named because
   * Java flagged them, and the wording is what Java's booleans assert - not an
   * interpretation of the numbers beside them.
   */
  function batchStateChips(batch) {
    if (batch.expired) {
      return '<span class="row-chip is-danger">Expired</span>';
    }
    if (batch.depleted) {
      return '<span class="row-chip is-neutral">Depleted</span>';
    }
    if (batch.sellable) {
      return '<span class="row-chip is-neutral">Sellable</span>';
    }
    return '<span class="row-chip is-neutral">Not sellable</span>';
  }

  /** Recent sales, newest first as Java returned them. */
  function renderSales(item) {
    var container = document.getElementById('drawerSales');
    if (!container) {
      return;
    }

    var sales = item.recentSales || [];
    if (sales.length === 0) {
      container.innerHTML = '<p class="empty-note">No sales recorded in the visible history.</p>';
      return;
    }

    var unit = item.unit;
    var html = ''
      + '<table class="drawer-table">'
      +   '<thead><tr>'
      +     '<th scope="col">Sale</th>'
      +     '<th scope="col">Date</th>'
      +     '<th scope="col">Qty</th>'
      +     '<th scope="col">Price</th>'
      +     '<th scope="col">Revenue</th>'
      +     '<th scope="col">Taken from</th>'
      +   '</tr></thead>'
      +   '<tbody>';

    for (var i = 0; i < sales.length; i++) {
      var s = sales[i];
      html += ''
        + '<tr data-sale-id="' + Format.escapeHtml(s.saleId) + '">'
        +   '<td>' + Format.escapeHtml(s.saleId) + '</td>'
        +   '<td>' + javaDate(s.date) + '</td>'
        +   '<td>' + withUnit(s.quantity, unit) + '</td>'
        +   '<td>' + Format.money(s.unitPrice) + '</td>'
        +   '<td>' + Format.money(s.revenue) + '</td>'
        +   '<td>' + drawsHtml(s.batchDraws) + '</td>'
        + '</tr>';
    }

    html += '</tbody></table>';
    container.innerHTML = html;
  }

  /**
   * Java's `batchDraws` string, printed as it stands.
   *
   * It arrives as text such as "B0001:5" - possibly several, separated - and
   * is deliberately not split into parts. Parsing it would be this page
   * rebuilding a structure Java chose to send as a sentence.
   */
  function drawsHtml(value) {
    var text = Format.text(value);
    if (text === Format.DASH) {
      return '<span class="fact-muted">' + Format.DASH + '</span>';
    }
    return Format.escapeHtml(text);
  }

  /**
   * The working behind each figure, exactly as Java wrote it.
   *
   * These are Java's sentences, so they are printed verbatim: rewriting one
   * would be this page explaining a calculation it did not perform.
   */
  function renderBreakdown(item) {
    var container = document.getElementById('drawerBreakdown');
    if (!container) {
      return;
    }

    var b = item.breakdown;
    if (!b) {
      container.innerHTML = '<p class="empty-note">The working was not supplied for this item.</p>';
      return;
    }

    var rows = [
      ['Velocity', b.velocityFormula],
      ['Season', b.seasonalFormula],
      ['Cover', b.coverFormula],
      ['Reorder point', b.reorderPointFormula],
      ['Order quantity', b.reorderQuantityFormula]
    ];

    var html = '';
    for (var i = 0; i < rows.length; i++) {
      html += ''
        + '<div class="formula-row">'
        +   '<div class="formula-label">' + Format.escapeHtml(rows[i][0]) + '</div>'
        +   '<code class="formula-text">' + Format.escapeHtml(Format.text(rows[i][1])) + '</code>'
        + '</div>';
    }
    container.innerHTML = html;
  }

  /* ----------------------------------------------------------- drawer states */

  /** Shown while the item request is in flight. */
  function renderLoading(itemId) {
    setText('drawerTitle', 'Loading\u2026');
    setText('drawerSubtitle', itemId ? 'Fetching ' + itemId + ' from the server.' : '');
    paintPlaceholder('Loading this item from the server\u2026');
  }

  /** Shown when the item could not be fetched. Says what Java said. */
  function renderFailure(itemId, message) {
    setText('drawerTitle', 'Could not load this item');
    setText('drawerSubtitle', itemId ? String(itemId) : '');
    paintPlaceholder(message);
  }

  /**
   * Clears the data areas and puts a single note in the body.
   *
   * The three data sections are emptied rather than left showing the previous
   * item's batches or sales, which would be worse than showing nothing. Their
   * headings are hidden with them, so a loading or failed drawer does not show
   * a list of empty section titles above one message.
   */
  function paintPlaceholder(message) {
    var note = '<p class="empty-note">' + Format.escapeHtml(message) + '</p>';
    var ids = ['drawerFacts', 'drawerStock', 'drawerBatches', 'drawerSales', 'drawerBreakdown'];
    for (var i = 0; i < ids.length; i++) {
      var element = document.getElementById(ids[i]);
      if (element) {
        element.innerHTML = '';
      }
    }
    setSectionsHidden(true);
    var body = document.querySelector('.drawer-body');
    var notice = document.getElementById('drawerNotice');
    if (!notice && body) {
      notice = document.createElement('p');
      notice.id = 'drawerNotice';
      body.insertBefore(notice, body.firstChild);
    }
    if (notice) {
      notice.className = 'empty-note drawer-notice';
      notice.innerHTML = note;
      notice.hidden = false;
    }
  }

  /** Hides or shows the drawer's section headings. */
  function setSectionsHidden(hidden) {
    var headings = document.querySelectorAll('.drawer-body .drawer-section-title');
    for (var i = 0; i < headings.length; i++) {
      headings[i].hidden = hidden;
    }
  }

  /** Removes any loading or failure note, before a real render. */
  function clearPlaceholder() {
    var notice = document.getElementById('drawerNotice');
    if (notice) {
      notice.hidden = true;
      notice.innerHTML = '';
    }
    setSectionsHidden(false);
  }

  function setText(id, value) {
    var element = document.getElementById(id);
    if (element) {
      element.textContent = value;
    }
  }

  global.StockPulseDrawer = {
    render: function (item) {
      clearPlaceholder();
      render(item);
    },
    renderLoading: renderLoading,
    renderFailure: renderFailure
  };
}(window));
