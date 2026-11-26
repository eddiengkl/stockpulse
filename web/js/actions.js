/*
 * StockPulse — actions.js
 *
 * The sales controls inside the item drawer: recording a sale, and undoing the
 * most recent one.
 *
 * The rule this file keeps:
 *
 *   The form sends what was typed and prints what Java answers. It does not
 *   decide whether a sale is allowed, how much stock is left, which batch was
 *   drawn from, or whether a role may undo. Every one of those answers arrives
 *   in the response, or in the error the server returns.
 *
 *   There is no arithmetic here. The quantity field is passed through as a
 *   string, and the price only as an optional value; the server reads a
 *   missing price as "use the item's own price".
 *
 * Java's own sentences are shown as supplied: `message` from a sale, and
 * `message` from an undo - including the case where there was nothing to undo,
 * which is an answer rather than an error.
 */

(function (global) {
  'use strict';

  var Format = global.StockPulseFormat;

  /* --------------------------------------------------------------- elements */

  function el(id) {
    return document.getElementById(id);
  }

  /* ---------------------------------------------------------------- writing */

  /**
   * Fills the sale form with the item it will act on.
   *
   * Called whenever the drawer renders an item, so the fields and buttons
   * always belong to the id on screen.
   *
   * @param {object} item the `data` of GET /api/items/{id}
   * @param {boolean} mayUndo whether Java's bootstrap said this role may undo
   * @param {boolean} [keepMessage] true when this render is the refresh that
   *        follows a sale or undo, so the answer just shown is not wiped
   */
  function bindItem(item, mayUndo, keepMessage) {
    var section = el('drawerActionsSection');
    if (!section || !item) {
      return;
    }

    section.hidden = false;
    section.setAttribute('data-item-id', item.itemId);

    var qty = el('saleQty');
    var price = el('salePrice');
    if (qty) {
      qty.value = '';
      qty.placeholder = 'e.g. 2 ' + (item.unit || '');
    }
    if (price) {
      price.value = '';
      price.placeholder = 'leave blank to use ' + Format.money(item.sellingPrice);
    }

    // Undo is a Manager action in Java. The button is hidden for Staff rather
    // than left to fail, since a control that can only produce a refusal is
    // not worth showing. The server still enforces the rule if this is wrong.
    var undo = el('undoSaleBtn');
    if (undo) {
      undo.hidden = !mayUndo;
    }

    if (!keepMessage) {
      setMessage('', '');
      setDeliveryMessage('', '');
    }
    bindDeliveryForm(item);
  }

  /**
   * The delivery form's own per-item wording.
   *
   * `canExpire` is Java's flag, so it decides what the hint says. The hint is
   * only guidance: the field stays editable either way, and Java still rejects
   * a perishable delivery with no expiry. That refusal is shown as sent.
   */
  function bindDeliveryForm(item) {
    var expiry = el('deliveryExpiry');
    var hint = el('deliveryExpiryHint');
    var block = el('deliveryBlock');
    if (!block) {
      return;
    }
    block.hidden = false;

    if (expiry) {
      expiry.value = '';
    }
    if (hint) {
      hint.textContent = item.canExpire
        ? 'Required for this item. Java rejects a delivery with no expiry, or one already in the past.'
        : 'Not used for this item: it does not expire.';
      hint.hidden = false;
    }
  }

  /** The item the panel is currently for, or null. */
  function boundItemId() {
    var section = el('drawerActionsSection');
    return section ? section.getAttribute('data-item-id') : null;
  }

  /** Shows a message under the buttons. `tone` is 'ok', 'warn' or 'error'. */
  function setMessage(text, tone) {
    var note = el('saleNote');
    if (!note) {
      return;
    }
    note.textContent = text || '';
    note.className = 'sale-note' + (tone ? ' is-' + tone : '');
  }

  /**
   * Prints Java's answer to a sale.
   *
   * `message` and `remainingDescription` are both Java's own sentences, so the
   * text is shown as written. Nothing is recomputed here - the stock figure in
   * the sentence is the one Java wrote.
   */
  function showSaleResult(result) {
    if (!result) {
      return;
    }
    setMessage(Format.text(result.message), 'ok');
  }

  /** Prints Java's answer to an undo, including "there was nothing to undo". */
  function showUndoResult(result) {
    if (!result) {
      return;
    }
    var text = Format.text(result.message);
    setMessage(text, result.undone ? 'ok' : 'warn');
  }

  /** Prints the server's reason for refusing, as it wrote it. */
  function showFailure(error) {
    var text = (error && error.describe) ? error.describe() : 'The request could not be completed.';
    setMessage(text, 'error');
  }

  /** Disables both buttons while a request is in flight. */
  function setBusy(busy) {
    var submit = el('saleSubmit');
    var undo = el('undoSaleBtn');
    if (submit) {
      submit.disabled = busy;
    }
    if (undo) {
      undo.disabled = busy;
    }
  }

  /* -------------------------------------------------------------- delivery */

  /** Shows a message under the delivery button. */
  function setDeliveryMessage(text, tone) {
    var note = el('deliveryNote');
    if (!note) {
      return;
    }
    note.textContent = text || '';
    note.className = 'sale-note' + (tone ? ' is-' + tone : '');
  }

  /**
   * Prints Java's answer to a delivery.
   *
   * `message` is Java's sentence ("Received 12 L of Fresh Milk 1L as B0008."),
   * so it is shown exactly as written. The new batch, the remaining stock and
   * the description all arrive in the payload; none of them is computed here.
   */
  function showDeliveryResult(result) {
    if (!result) {
      return;
    }
    setDeliveryMessage(Format.text(result.message), 'ok');
  }

  /** Prints the server's reason for refusing a delivery, as it wrote it. */
  function showDeliveryFailure(error) {
    var text = (error && error.describe) ? error.describe() : 'The delivery could not be recorded.';
    setDeliveryMessage(text, 'error');
  }

  /** Disables the delivery button while its request is in flight. */
  function setDeliveryBusy(busy) {
    var submit = el('deliverySubmit');
    if (submit) {
      submit.disabled = busy;
    }
  }

  /** Clears the panel, used when the drawer has no item to act on. */
  function reset() {
    var section = el('drawerActionsSection');
    if (section) {
      section.hidden = true;
      section.removeAttribute('data-item-id');
    }
    setMessage('', '');
    setDeliveryMessage('', '');
  }

  global.StockPulseActions = {
    bindItem: bindItem,
    boundItemId: boundItemId,
    showSaleResult: showSaleResult,
    showUndoResult: showUndoResult,
    showFailure: showFailure,
    setBusy: setBusy,
    showDeliveryResult: showDeliveryResult,
    showDeliveryFailure: showDeliveryFailure,
    setDeliveryBusy: setDeliveryBusy,
    reset: reset
  };
}(window));
