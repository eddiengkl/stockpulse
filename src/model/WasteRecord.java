package model;

import java.time.LocalDate;

/**
 * Stock that was thrown away, recorded against the batch so waste can be traced back
 * to a delivery. Expired stock is never sold, so it leaves the shelf through here.
 */
public class WasteRecord {

    private String wasteId;
    private String batchId;
    private String itemId;
    private LocalDate date;
    private double quantity;   // > 0
    private double unitCost;   // retained so waste value stays accurate if costs change, >= 0
    private String reason;

    /**
     * @throws IllegalArgumentException when the ids are blank and contain '|', the date
     *                                  is missing, or a number is out of range
     */
    public WasteRecord(String wasteId, String batchId, String itemId, LocalDate date,
                       double quantity, double unitCost, String reason) {
        this.wasteId = DataText.safeField(wasteId, "waste id");
        this.batchId = DataText.safeField(batchId, "batch id");
        this.itemId = DataText.safeField(itemId, "item id");
        if (date == null) {
            throw new IllegalArgumentException("Waste date is required.");
        }
        this.date = date;
        if (!(quantity > 0) || !Double.isFinite(quantity)) {
            throw new IllegalArgumentException(
                    "Waste quantity must be a real number above 0 but was "
                            + (Double.isFinite(quantity) ? NumberText.quantity(quantity) : String.valueOf(quantity))
                            + ".");
        }
        this.quantity = quantity;
        this.unitCost = DataText.nonNegative(unitCost, "waste unit cost");
        this.reason = (reason == null || reason.trim().isEmpty())
                ? "unspecified"
                : DataText.safeField(reason, "waste reason");
    }

    /** Money lost to waste, in RM. */
    public double valueLost() {
        return quantity * unitCost;
    }

    public String toDataLine() {
        return wasteId + "|" + batchId + "|" + itemId + "|" + DateText.format(date) + "|"
                + NumberText.quantity(quantity) + "|" + NumberText.money(unitCost) + "|" + reason;
    }

    public String getWasteId() { return wasteId; }
    public String getBatchId() { return batchId; }
    public String getItemId() { return itemId; }
    public LocalDate getDate() { return date; }
    public double getQuantity() { return quantity; }
    public double getUnitCost() { return unitCost; }
    public String getReason() { return reason; }
}
