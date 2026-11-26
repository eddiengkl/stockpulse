/*
 * StockPulse — dashboard.js
 *
 * Renders the Dashboard from GET /api/dashboard and GET /api/advisor/briefing.
 *
 * The rule this file keeps, and the reason it is a separate file:
 *
 *   Every number on this screen is copied straight out of the JSON. Nothing
 *   is added, subtracted, sorted, ranked or recomputed here. The KPI values
 *   are read one-for-one from `totals`; the two queues are `flags[]` filtered
 *   by type and left in the order Java ranked them.
 *
 * "Filtered by type" is the only operation performed on the flag list, and it
 * is not a ranking: it splits one server-ordered list into the two cards that
 * display it. The relative order within each card is untouched.
 */

(function (global) {
  'use strict';

  var Format = global.StockPulseFormat;

  /*
   * The six KPI cards, each tied to one field of `totals`.
   *
   * `kind` chooses how the value is written, never what it is: "money" gets a
   * currency prefix and two decimals, "count" is printed as sent. A value that
   * is missing shows a dash rather than a confident zero.
   *
   * Nothing here decides a colour. Three of these figures count warnings, but
   * the API sends a count and no severity, so there is no basis for the page to
   * paint one differently from another — and the markup is already neutral, so
   * a count of 2 and a count of 0 look the same in every state, including
   * before the first response arrives. Any emphasis on these numbers belongs to
   * Java, not to this file.
   */
  var KPI_FIELDS = [
    { figure: 'itemCount', kind: 'count' },
    { figure: 'stockValueAtCost', kind: 'money' },
    { figure: 'stockoutCount', kind: 'count' },
    { figure: 'expiryCount', kind: 'count' },
    { figure: 'expiredCount', kind: 'count' },
    { figure: 'wasteValueInWindow', kind: 'money' }
  ];

  /** Which flag types belong in which queue, in the API's own vocabulary. */
  var RESTOCK_TYPES = ['STOCKOUT'];
  var EXPIRY_TYPES = ['EXPIRY', 'EXPIRED'];

  /* ------------------------------------------------------------------ render */

  /**
   * Draws the whole dashboard from two payloads.
   *
   * @param {object} dashboard the `data` of GET /api/dashboard
   * @param {object} briefing  the `data` of GET /api/advisor/briefing
   */
  function render(dashboard, briefing) {
    if (!dashboard) {
      return;
    }
    renderKpis(dashboard.totals);
    renderQueues(dashboard.flags || []);
    if (briefing) {
      renderBriefing(briefing);
    }
  }

  /* -------------------------------------------------------------------- KPIs */

  function renderKpis(totals) {
    if (!totals) {
      return;
    }

    for (var i = 0; i < KPI_FIELDS.length; i++) {
      var field = KPI_FIELDS[i];
      var element = document.querySelector('[data-figure="' + field.figure + '"]');
      if (!element) {
        continue;
      }
      var value = totals[field.figure];
      element.textContent = (field.kind === 'money')
        ? Format.money(value)
        : Format.count(value);
    }

    // The waste card's subtitle names the window the figure covers, using the
    // window length Java reported rather than a hard-coded 14.
    var windowNote = document.querySelector('[data-figure="wasteWindow"]');
    if (windowNote) {
      windowNote.textContent = (totals.windowDays === null || totals.windowDays === undefined)
        ? 'In the current window'
        : 'In the last ' + Format.count(totals.windowDays) + ' days';
    }
  }

  /* ------------------------------------------------------------------ queues */

  /**
   * Splits `flags[]` into the two cards.
   *
   * Order is preserved exactly: this walks the array once per queue and
   * appends in the sequence Java produced. No comparison function is used
   * anywhere, so `urgency` cannot be re-sorted here even by accident.
   */
  function renderQueues(flags) {
    var restock = [];
    var expiry = [];

    for (var i = 0; i < flags.length; i++) {
      var flag = flags[i];
      var type = String(flag.type || '').toUpperCase();
      if (RESTOCK_TYPES.indexOf(type) !== -1) {
        restock.push(flag);
      } else if (EXPIRY_TYPES.indexOf(type) !== -1) {
        expiry.push(flag);
      }
    }

    paintQueue('restockQueue', restock, 'No items need restocking right now.');
    paintQueue('expiryQueue', expiry, 'No batches are close to expiring.');
  }

  function paintQueue(containerId, flags, emptyMessage) {
    var container = document.getElementById(containerId);
    if (!container) {
      return;
    }

    if (flags.length === 0) {
      container.innerHTML = '<p class="empty-note">' + Format.escapeHtml(emptyMessage) + '</p>';
      return;
    }

    var html = '';
    for (var i = 0; i < flags.length; i++) {
      html += flagRow(flags[i]);
    }
    container.innerHTML = html;
  }

  /**
   * One warning row.
   *
   * The row shows three things Java supplied: the item name, its unit, and the
   * reason sentence. The reason is Java's own wording — "Only 0.2 days of cover
   * and a delivery takes 1 day plus a 1-day safety margin." — which is why no
   * quantity or day count appears here as a number this page made up.
   */
  function flagRow(flag) {
    var name = flag.itemName ? flag.itemName : flag.itemId;
    var chipClass = 'queue-chip ' + Format.riskClass(flag.type);
    var label = Format.riskLabel(flag.type);

    // A batch-level flag carries a batch id; an item-level one does not. It is
    // shown when present because the reason may refer to it.
    var meta = [];
    if (flag.itemId) {
      meta.push(Format.escapeHtml(flag.itemId));
    }
    if (flag.batchId) {
      meta.push('batch ' + Format.escapeHtml(flag.batchId));
    }

    return ''
      + '<div class="queue-row">'
      +   '<div class="queue-row-head">'
      +     '<span class="queue-name">' + Format.escapeHtml(name) + '</span>'
      +     '<span class="' + chipClass + '">' + Format.escapeHtml(label) + '</span>'
      +   '</div>'
      +   '<p class="queue-reason">' + Format.escapeHtml(flag.reason) + '</p>'
      +   (meta.length
            ? '<p class="queue-meta">' + meta.join(' \u00B7 ') + '</p>'
            : '')
      + '</div>';
  }

  /* ---------------------------------------------------------------- briefing */

  /**
   * Draws the advisor card.
   *
   * Three states, and they are deliberately distinguishable:
   *
   *   source === "AI"      the model's text, labelled as AI
   *   otherwise            no AI text at all; `unavailable.message` and
   *                        `unavailable.remediation` are shown instead
   *
   * `ruleSummary` is always written by Java and always labelled as such. It is
   * never placed in the AI paragraph, so the two sources cannot be confused.
   */
  function renderBriefing(briefing) {
    renderBriefingText(briefing);
    renderAdvisorChip(briefing);
    renderRuleSummary(briefing.ruleSummary);
  }

  function renderBriefingText(briefing) {
    var body = document.getElementById('advisorText');
    if (!body) {
      return;
    }

    if (briefing.source === 'AI' && briefing.briefing) {
      // Paragraph breaks in the model's reply are preserved; each line is
      // escaped, so model output cannot inject markup.
      body.className = 'briefing-text';
      body.innerHTML = paragraphs(briefing.briefing);
      return;
    }

    // No AI text. Say why, in the server's words, and what would fix it.
    var unavailable = briefing.unavailable || {};
    var html = ''
      + '<span class="briefing-lead">No AI briefing was produced.</span> '
      + Format.escapeHtml(unavailable.message || 'The advisor is not available.');

    if (unavailable.remediation) {
      html += '<span class="briefing-remedy">'
        + Format.escapeHtml(unavailable.remediation)
        + '</span>';
    }

    body.className = 'briefing-text is-unavailable';
    body.innerHTML = html;
  }

  /** The chip beside the Advisor Briefing title says where the text came from. */
  function renderAdvisorChip(briefing) {
    var chip = document.getElementById('advisorSourceChip');
    if (!chip) {
      return;
    }

    if (briefing.source === 'AI') {
      chip.textContent = 'Written by AI';
      chip.className = 'source-chip is-ai';
      chip.title = briefing.model
        ? 'Generated by ' + briefing.model + ' at ' + briefing.generatedAt
        : '';
    } else {
      chip.textContent = 'AI unavailable';
      chip.className = 'source-chip';
      chip.title = briefing.unavailable && briefing.unavailable.reason
        ? 'Reason: ' + briefing.unavailable.reason
        : '';
    }
  }

  function renderRuleSummary(ruleSummary) {
    var element = document.getElementById('ruleSummaryText');
    if (!element || !ruleSummary) {
      return;
    }
    element.textContent = ruleSummary.text || '';
  }

  /** Escaped paragraphs, preserving blank-line breaks from the model. */
  function paragraphs(text) {
    var blocks = String(text).split(/\n{2,}/);
    var html = [];
    for (var i = 0; i < blocks.length; i++) {
      var block = blocks[i].trim();
      if (block) {
        // Single newlines inside a block become line breaks, not new paragraphs.
        html.push('<p>' + Format.escapeHtml(block).replace(/\n/g, '<br />') + '</p>');
      }
    }
    return html.join('');
  }

  global.StockPulseDashboard = {
    render: render
  };
}(window));
