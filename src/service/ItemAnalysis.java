package service;

import java.time.LocalDate;

import model.Batch;
import model.RiskFlag;
import model.Settings;
import model.StockItem;
import store.BatchStore;
import store.ItemStore;

/**
 * Everything the dashboard needs to know about one item, computed once.
 *
 * <p>This object exists so that the numbers on screen come from one place. The dashboard
 * list, the item detail page and the AI advisor's fact sheet all read these figures
 * instead of each recalculating demand in a slightly different way.</p>
 *
 * <p>It is a snapshot: it is built from the stores at one moment and never changes
 * them.</p>
 */
public class ItemAnalysis {

    public final StockItem item;
    public final LocalDate today;

    /** Stock on hand across all batches that have not been written off. */
    public final double stockOnHand;
    /** Batches currently holding stock, expired ones included. */
    public final int batchCount;
    /** Stock that may still be sold today: non-zero and not past its expiry date. */
    public final double sellableStock;

    public final double observedVelocity;
    public final double seasonalVelocity;
    public final int velocityDivisor;
    /** The seasonal multiplier actually applied, 1.0 when not in season. */
    public final double seasonalMultiplier;

    public final double daysOfCover;
    public final double reorderPoint;
    public final boolean stockoutRisk;
    public final double stockoutUrgency;
    public final double reorderQuantity;

    /** Earliest expiry across batches, or Integer.MAX_VALUE for long-life stock. */
    public final int daysUntilExpiry;
    /** One-line polymorphic description from the item subclass. */
    public final String stockDescription;

    /** Total stock value at cost, used as the final, visible tie-break. */
    public final double stockValueAtCost;
    public final double stockValueAtPrice;

    ItemAnalysis(StockItem item, LocalDate today, double stockOnHand, int batchCount,
                 double sellableStock, double observedVelocity, double seasonalVelocity,
                 int velocityDivisor, double seasonalMultiplier, double daysOfCover,
                 double reorderPoint, boolean stockoutRisk, double stockoutUrgency,
                 double reorderQuantity, int daysUntilExpiry, String stockDescription,
                 double stockValueAtCost, double stockValueAtPrice) {
        this.item = item;
        this.today = today;
        this.stockOnHand = stockOnHand;
        this.batchCount = batchCount;
        this.sellableStock = sellableStock;
        this.observedVelocity = observedVelocity;
        this.seasonalVelocity = seasonalVelocity;
        this.velocityDivisor = velocityDivisor;
        this.seasonalMultiplier = seasonalMultiplier;
        this.daysOfCover = daysOfCover;
        this.reorderPoint = reorderPoint;
        this.stockoutRisk = stockoutRisk;
        this.stockoutUrgency = stockoutUrgency;
        this.reorderQuantity = reorderQuantity;
        this.daysUntilExpiry = daysUntilExpiry;
        this.stockDescription = stockDescription;
        this.stockValueAtCost = stockValueAtCost;
        this.stockValueAtPrice = stockValueAtPrice;
    }

    public String itemId() {
        return item.getItemId();
    }

    /** True when days of cover is infinite, i.e. nothing is selling. */
    public boolean neverRunsOut() {
        return !Double.isFinite(daysOfCover);
    }

    /** True when this item cannot expire, so the expiry column is meaningless for it. */
    public boolean noExpiry() {
        return daysUntilExpiry == Integer.MAX_VALUE;
    }
}
