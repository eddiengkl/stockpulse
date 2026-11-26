package model;

import java.time.LocalDate;

/**
 * One delivery lot of one item.
 *
 * <p>Two cartons of milk bought a week apart have different expiry dates, so stock is
 * tracked per batch rather than per item. Sales are taken from the batch that expires
 * soonest (first-expired, first-out), which is why {@code qtyRemaining} and the
 * received quantity are separate values.</p>
 *
 * <p>Every value is validated here rather than in the service layer, so an invalid
 * batch cannot exist in memory in the first place. These are unchecked
 * {@link IllegalArgumentException}s: the service layer turns user input into these
 * fields and reports problems to the user with its own custom exceptions, while these
 * checks guard against programming mistakes.</p>
 */
public class Batch {

    private String batchId;       // unique, e.g. "B0041"
    private String itemId;        // which StockItem this batch belongs to
    private double qtyReceived;   // what arrived, > 0
    private double qtyRemaining;  // what is still on the shelf, 0..qtyReceived
    private LocalDate receivedDate;
    private LocalDate expiryDate; // null for stock that cannot expire
    private double unitCost;      // cost per unit at the time of this delivery, >= 0

    /**
     * @param batchId      unique batch id
     * @param itemId       owning item id
     * @param qtyReceived  quantity delivered, must be &gt; 0
     * @param qtyRemaining quantity still on hand, must be between 0 and {@code qtyReceived}
     * @param receivedDate day the delivery arrived, required
     * @param expiryDate   use-by date, or null when this stock cannot expire; may not be
     *                     before {@code receivedDate}
     * @param unitCost     cost per unit for this delivery, must be &gt;= 0
     * @throws IllegalArgumentException when any of the above does not hold
     */
    public Batch(String batchId, String itemId, double qtyReceived, double qtyRemaining,
                 LocalDate receivedDate, LocalDate expiryDate, double unitCost) {
        // Validate through the static checks, not the setters, so a half-built object
        // is never handed to a method that a subclass could override.
        this.batchId = requireText(batchId, "batch id");
        this.itemId = requireText(itemId, "item id");
        this.receivedDate = requireDate(receivedDate, "received date");
        this.expiryDate = checkExpiryDate(expiryDate, this.receivedDate);
        this.qtyReceived = checkReceived(qtyReceived, this.batchId);
        this.qtyRemaining = checkRemaining(qtyRemaining, this.qtyReceived, this.batchId);
        this.unitCost = checkCost(unitCost, this.batchId);
    }

    // ------------------------------------------------------------- validation

    /**
     * Changes how much arrived.
     *
     * <p>A lower received quantity below what is still on the shelf is rejected rather
     * than quietly trimming the remaining stock: trimming would make stock disappear
     * without a sale, a waste record or an activity line, which is exactly the silent
     * data loss to avoid.</p>
     *
     * @throws IllegalArgumentException when the quantity is not positive, or is lower
     *                                  than the quantity still on the shelf
     */
    public void setQtyReceived(double qtyReceived) {
        double checked = checkReceived(qtyReceived, batchId);
        if (checked < this.qtyRemaining) {
            throw new IllegalArgumentException(
                    "Batch " + batchId + " already holds " + NumberText.quantity(this.qtyRemaining)
                            + " remaining, so the received quantity cannot be lowered to "
                            + NumberText.quantity(checked)
                            + ". Record a sale or waste first.");
        }
        this.qtyReceived = checked;
    }

    /** @throws IllegalArgumentException when the quantity is negative, not a number, or above what arrived */
    public void setQtyRemaining(double qtyRemaining) {
        this.qtyRemaining = checkRemaining(qtyRemaining, this.qtyReceived, this.batchId);
    }

    /** @throws IllegalArgumentException when the cost is negative or not a number */
    public void setUnitCost(double unitCost) {
        this.unitCost = checkCost(unitCost, this.batchId);
    }

    private static double checkReceived(double qtyReceived, String batchId) {
        if (!(qtyReceived > 0)) {   // written this way so NaN is rejected as well
            throw new IllegalArgumentException(
                    "Batch " + batchId + " received quantity must be more than 0 but was "
                            + NumberText.quantity(qtyReceived) + ".");
        }
        return qtyReceived;
    }

    private static double checkRemaining(double qtyRemaining, double qtyReceived, String batchId) {
        if (Double.isNaN(qtyRemaining) || qtyRemaining < 0) {
            throw new IllegalArgumentException(
                    "Batch " + batchId + " remaining quantity cannot be negative but was "
                            + NumberText.quantity(qtyRemaining) + ".");
        }
        if (qtyRemaining > qtyReceived) {
            throw new IllegalArgumentException(
                    "Batch " + batchId + " cannot hold " + NumberText.quantity(qtyRemaining)
                            + " remaining when only " + NumberText.quantity(qtyReceived)
                            + " was received.");
        }
        return qtyRemaining;
    }

    private static double checkCost(double unitCost, String batchId) {
        if (Double.isNaN(unitCost) || unitCost < 0) {
            throw new IllegalArgumentException(
                    "Batch " + batchId + " unit cost cannot be negative but was "
                            + NumberText.quantity(unitCost) + ".");
        }
        return unitCost;
    }

    /**
     * @throws IllegalArgumentException when the date is before the received date, which
     *                                  would mean the stock was already expired on arrival
     */
    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = checkExpiryDate(expiryDate, this.receivedDate);
    }

    public void setBatchId(String batchId) {
        this.batchId = requireText(batchId, "batch id");
    }

    /**
     * An independent copy of this batch, keeping the same id and quantities.
     *
     * <p>Used by the snapshot taken before a change is saved. A shallow array copy is not
     * enough: {@link #take(double)} changes a batch in place, so restoring a rolled-back
     * sale needs new batch objects, not the same ones pointing at the reduced figures.</p>
     */
    public Batch copy() {
        return new Batch(batchId, itemId, qtyReceived, qtyRemaining, receivedDate, expiryDate,
                unitCost);
    }

    private static String requireText(String text, String fieldName) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Batch " + fieldName + " cannot be empty.");
        }
        if (text.indexOf('|') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "Batch " + fieldName + " cannot contain '|' or line breaks.");
        }
        return text.trim();
    }

    private static LocalDate requireDate(LocalDate date, String fieldName) {
        if (date == null) {
            throw new IllegalArgumentException("Batch " + fieldName + " is required.");
        }
        return date;
    }

    private static LocalDate checkExpiryDate(LocalDate expiryDate, LocalDate receivedDate) {
        if (expiryDate != null && expiryDate.isBefore(receivedDate)) {
            throw new IllegalArgumentException(
                    "Batch expiry date " + DateText.format(expiryDate)
                            + " is before the received date " + DateText.format(receivedDate) + ".");
        }
        return expiryDate;
    }

    // ---------------------------------------------------------------- behaviour

    /** True when the batch has an expiry date and it is already past. */
    public boolean isExpired(LocalDate today) {
        return expiryDate != null && expiryDate.isBefore(today);
    }

    /** Days from {@code today} until this batch expires. Long-life batches return MAX_VALUE. */
    public int daysLeft(LocalDate today) {
        if (expiryDate == null) {
            return Integer.MAX_VALUE;
        }
        long days = expiryDate.toEpochDay() - today.toEpochDay();
        return (days > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) days;
    }

    public boolean isDepleted() {
        return qtyRemaining <= 0.0000001;
    }

    /** Value of what is left in this batch, in RM. */
    public double remainingValue() {
        return qtyRemaining * unitCost;
    }

    /**
     * Takes stock out of this batch, never more than is there.
     *
     * <p>The positive-quantity check matters: without it a negative {@code qty} would
     * subtract a negative and silently <em>add</em> stock to the batch.</p>
     *
     * @param qty quantity wanted, must be &gt; 0
     * @return the quantity actually taken
     * @throws IllegalArgumentException when {@code qty} is not positive
     */
    public double take(double qty) {
        if (!(qty > 0)) {
            throw new IllegalArgumentException(
                    "Cannot take " + NumberText.quantity(qty) + " from batch " + batchId
                            + "; quantity must be more than 0.");
        }
        double taken = Math.min(qty, qtyRemaining);
        qtyRemaining -= taken;
        return taken;
    }

    public String toDataLine() {
        return batchId + "|" + itemId + "|" + NumberText.quantity(qtyReceived) + "|"
                + NumberText.quantity(qtyRemaining) + "|" + DateText.format(receivedDate) + "|"
                + (expiryDate == null ? "-" : DateText.format(expiryDate)) + "|"
                + NumberText.money(unitCost);
    }

    public String getBatchId() { return batchId; }
    public String getItemId() { return itemId; }
    public double getQtyReceived() { return qtyReceived; }
    public double getQtyRemaining() { return qtyRemaining; }
    public LocalDate getReceivedDate() { return receivedDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public double getUnitCost() { return unitCost; }
}
