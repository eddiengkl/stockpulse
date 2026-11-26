/*
 * StockPulse — format.js
 *
 * Text helpers only.
 *
 * This file deliberately contains NO arithmetic on any figure that represents
 * stock, money, days, velocity or risk. Those numbers already arrive from Java
 * as finished values together with the words that describe them
 * (`daysOfCoverText`, `stockDescription`, `discountText`, `stockValueFormula`),
 * and the page's job is to place that text, not to recompute it.
 *
 * The only numeric transformation here is rounding a value Java sent for a
 * narrow display reason — two decimal places on a money figure — and that is
 * flagged where it happens.
 *
 * Everything user-supplied goes through escapeHtml before it reaches innerHTML.
 */

(function (global) {
  'use strict';

  var HTML_ESCAPES = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  };

  /**
   * Escapes text for insertion into HTML.
   *
   * Item names and supplier names are typed by a user, and a name containing
   * an angle bracket must render as text rather than as markup.
   */
  function escapeHtml(value) {
    if (value === null || value === undefined) {
      return '';
    }
    return String(value).replace(/[&<>"']/g, function (character) {
      return HTML_ESCAPES[character];
    });
  }

  /** The dash used wherever a value is not available. One character, everywhere. */
  var DASH = '\u2014';

  /** Text for display, with a dash standing in for null, undefined or ''. */
  function text(value) {
    if (value === null || value === undefined || value === '') {
      return DASH;
    }
    return String(value);
  }

  /**
   * Text that may be empty, for cells where a blank means "not applicable"
   * rather than "missing" — an item that cannot expire has no expiry column.
   */
  function textOrEmpty(value) {
    if (value === null || value === undefined) {
      return '';
    }
    return String(value);
  }

  /**
   * A money figure Java sent, shown with two decimals.
   *
   * This rounds for display only; it does not total anything. Java has already
   * decided which stock counts, at which cost, and whether the value exists at
   * all — a null here means "no cost is known", and the dash says so rather
   * than printing a confident 0.00.
   */
  function money(value) {
    if (value === null || value === undefined || value === '') {
      return DASH;
    }
    var number = Number(value);
    if (!isFinite(number)) {
      return DASH;
    }
    return 'RM ' + number.toFixed(2);
  }

  /** A count Java sent, printed as given. No rounding, no grouping, no sums. */
  function count(value) {
    if (value === null || value === undefined || value === '') {
      return DASH;
    }
    return String(value);
  }

  /** Plain numbers on/off a control, where a dash would be wrong. */
  function valueOrZero(value) {
    if (value === null || value === undefined || value === '') {
      return '0';
    }
    return String(value);
  }

  /**
   * Maps a server risk type to a CSS modifier.
   *
   * The type strings come from model.RiskFlag: STOCKOUT, EXPIRY, EXPIRED. An
   * unrecognised type still renders — it is simply styled as neutral rather
   * than being dropped, so a future flag would be visible instead of silent.
   */
  var RISK_CLASS = {
    STOCKOUT: 'is-danger',
    EXPIRY: 'is-warn',
    EXPIRED: 'is-danger'
  };

  function riskClass(type) {
    var key = String(type || '').toUpperCase();
    return RISK_CLASS[key] || 'is-neutral';
  }

  /** A short label for a risk type, for the chip on a warning row. */
  var RISK_LABEL = {
    STOCKOUT: 'Restock',
    EXPIRY: 'Expiry',
    EXPIRED: 'Expired'
  };

  function riskLabel(type) {
    var key = String(type || '').toUpperCase();
    return RISK_LABEL[key] || 'Warning';
  }

  /** Title Case for a type or status word the server sent in caps. */
  function titleCase(word) {
    if (!word) {
      return '';
    }
    var lower = String(word).toLowerCase();
    return lower.charAt(0).toUpperCase() + lower.slice(1);
  }

  /**
   * A signed day count with a fixed unit, exactly as Java sent it.
   *
   * This exists because the item list supplies `daysUntilExpiry` as a bare
   * number and no phrase about it. Nothing is interpreted here: the sign is
   * kept, the number is not rounded, and the unit never changes form. A
   * negative value is written "-2 d", not "Expired", because deciding that a
   * negative number *means* expired is a business rule, and business rules
   * belong to Java.
   *
   * The unit is deliberately the same for every value — one length, no plural
   * — so the column reads as a set of measurements rather than as sentences
   * this file would be composing. The column heading says what "d" means.
   *
   * A null value is not printed as 0: the caller decides what to say instead,
   * usually using Java's own `noExpiry` flag.
   */
  function daysSigned(value) {
    if (value === null || value === undefined || value === '') {
      return DASH;
    }
    var number = Number(value);
    if (!isFinite(number)) {
      return DASH;
    }
    return String(value) + ' d';
  }

  /**
   * A plain day count with the same fixed unit, for values that cannot be
   * negative — a supplier lead time, for instance.
   *
   * Kept separate from `daysSigned` only so the signature is not used for a
   * quantity where a minus sign would be meaningless. The rule is identical:
   * Java's number, one unit, never "day"/"days" chosen by this file. Java's
   * own sentences use normal grammar ("12 bottles sold ..."); this helper is
   * never called on those.
   */
  function daysNumber(value) {
    if (value === null || value === undefined || value === '') {
      return DASH;
    }
    var number = Number(value);
    if (!isFinite(number)) {
      return DASH;
    }
    return String(value) + ' d';
  }

  global.StockPulseFormat = {
    DASH: DASH,
    escapeHtml: escapeHtml,
    text: text,
    textOrEmpty: textOrEmpty,
    money: money,
    count: count,
    valueOrZero: valueOrZero,
    riskClass: riskClass,
    riskLabel: riskLabel,
    titleCase: titleCase,
    daysSigned: daysSigned,
    daysNumber: daysNumber
  };
}(window));
