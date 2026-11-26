package model;

/**
 * One computed warning: this item's stock will run out, or this batch will still have
 * stock left on its expiry date, or this batch has already expired.
 *
 * <p>A RiskFlag is a result object. It is built by the risk engine (Phase 3B) and read
 * by the dashboard and the advisor; it never changes any stored data.</p>
 */
public class RiskFlag {

    public static final String TYPE_STOCKOUT = "STOCKOUT";
    public static final String TYPE_EXPIRY = "EXPIRY";
    public static final String TYPE_EXPIRED = "EXPIRED";

    private String itemId;
    private String batchId;       // null for stockout flags, which are item-level
    private String type;          // STOCKOUT, EXPIRY or EXPIRED
    private double urgency;       // higher = more urgent; meaning depends on type
    private String reason;        // plain-English why, built by the risk engine

    /**
     * @throws IllegalArgumentException when the type is not one of the three constants
     */
    public RiskFlag(String itemId, String batchId, String type, double urgency, String reason) {
        if (!TYPE_STOCKOUT.equals(type) && !TYPE_EXPIRY.equals(type) && !TYPE_EXPIRED.equals(type)) {
            throw new IllegalArgumentException("Unknown risk type '" + type + "'.");
        }
        this.itemId = DataText.safeField(itemId, "risk item id");
        this.batchId = (batchId == null) ? null : DataText.safeField(batchId, "risk batch id");
        this.type = type;
        this.urgency = urgency;
        this.reason = (reason == null) ? "" : reason.trim();
    }

    public boolean isItemLevel() {
        return batchId == null;
    }

    public String getItemId() { return itemId; }
    public String getBatchId() { return batchId; }
    public String getType() { return type; }
    public double getUrgency() { return urgency; }
    public String getReason() { return reason; }
}
