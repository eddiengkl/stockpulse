package service;

import java.time.LocalDate;

import model.Batch;
import model.NumberText;
import model.RiskFlag;
import model.Season;
import model.Settings;
import model.StockItem;
import store.BatchStore;
import store.ItemStore;
import store.SeasonStore;
import store.WasteStore;

/**
 * The business decisions behind the dashboard: which items are at risk, how badly, and
 * in what order.
 *
 * <p>This class exists because ranking and flag generation must happen in Java. Putting
 * a sort in {@code ApiHandler} or in JavaScript would move a business rule into a place
 * that is neither tested nor part of the report's argument, and the two copies would
 * drift apart.</p>
 *
 * <p>Ranking, as agreed: stockout risk first, then expiry risk, then total stock value
 * at cost as a visible tie-break. Expired stock is listed separately from expiry risk -
 * it is not a forecast, it is a fact that needs writing off.</p>
 */
public class DashboardService {

    private final ItemStore items;
    private final BatchStore batches;
    private final SeasonStore seasons;
    private final WasteStore waste;
    private final Settings settings;
    private final RiskEngine risk;
    private final StockAllocator allocator;

    public DashboardService(ItemStore items, BatchStore batches, SeasonStore seasons,
                            WasteStore waste, Settings settings, RiskEngine risk,
                            StockAllocator allocator) {
        this.items = items;
        this.batches = batches;
        this.seasons = seasons;
        this.waste = waste;
        this.settings = settings;
        this.risk = risk;
        this.allocator = allocator;
    }

    public LocalDate today() {
        return settings.effectiveDate();
    }

    // ------------------------------------------------------------- analyses

    /**
     * Computes every dashboard figure for one item.
     *
     * @throws exception.ItemNotFoundException when the item id is unknown
     */
    public ItemAnalysis analyse(StockItem item) {
        LocalDate today = today();
        String itemId = item.getItemId();

        double stockOnHand = batches.stockOf(itemId);
        int batchCount = batches.liveBatchCount(itemId);
        double sellable = allocator.saleableStock(itemId, today);

        double observed = risk.observedVelocity(itemId, today);
        double seasonal = risk.seasonalVelocity(item, observed);
        int divisor = risk.velocityDivisor(itemId, today);
        Season active = settings.resolveActiveSeason(seasons.seasons(), seasons.count());
        double multiplier = item.seasonalMultiplierFor(active);

        double cover = risk.daysOfCover(stockOnHand, seasonal);
        double point = risk.reorderPoint(seasonal, item.getLeadTimeDays());
        boolean atRisk = risk.isStockoutRisk(cover, item.getLeadTimeDays());
        double urgency = risk.stockoutUrgency(cover, item.getLeadTimeDays());
        double reorder = risk.reorderQuantity(stockOnHand, seasonal,
                item.getLeadTimeDays(), item.getPackSize());

        int daysLeft = item.daysUntilExpiry(batches.expiryEpochDaysFor(itemId), today);
        String description = item.describeStock(stockOnHand, batchCount, daysLeft);

        return new ItemAnalysis(item, today, stockOnHand, batchCount, sellable, observed,
                seasonal, divisor, multiplier, cover, point, atRisk, urgency, reorder,
                daysLeft, description, stockOnHand * item.getUnitCost(),
                stockOnHand * item.getSellingPrice());
    }

    /** Analyses every item currently in the store, in insertion order. */
    public ItemAnalysis[] analyseAll() {
        StockItem[] all = items.items();
        ItemAnalysis[] result = new ItemAnalysis[items.count()];
        int n = 0;
        for (int i = 0; i < items.count(); i++) {
            if (all[i] != null) {
                result[n++] = analyse(all[i]);
            }
        }
        if (n == result.length) {
            return result;
        }
        ItemAnalysis[] trimmed = new ItemAnalysis[n];
        for (int i = 0; i < n; i++) {
            trimmed[i] = result[i];
        }
        return trimmed;
    }

    // ---------------------------------------------------------------- flags

    /**
     * Every warning the dashboard can show, in display order.
     *
     * <p>Order of the three groups: items that will run out, batches that will still
     * have stock on their expiry date, then batches that have already expired. Within a
     * group the most urgent comes first; ties are broken by stock value at cost so the
     * expensive mistake is dealt with first.</p>
     */
    public RiskFlag[] flags() {
        ItemAnalysis[] analyses = analyseAll();
        LocalDate today = today();

        RiskFlag[] stockouts = new RiskFlag[analyses.length];
        int stockoutCount = 0;
        RiskFlag[] expiries = new RiskFlag[analyses.length * 4];
        int expiryCount = 0;
        RiskFlag[] expired = new RiskFlag[analyses.length * 4];
        int expiredCount = 0;

        for (int i = 0; i < analyses.length; i++) {
            ItemAnalysis analysis = analyses[i];
            StockItem item = analysis.item;

            if (analysis.stockoutRisk) {
                stockouts[stockoutCount++] = new RiskFlag(item.getItemId(), null,
                        RiskFlag.TYPE_STOCKOUT, analysis.stockoutUrgency,
                        stockoutReason(analysis));
            }

            RiskEngine.ExpiryProjection[] projections =
                    risk.projectExpiry(item.getItemId(), analysis.seasonalVelocity, today);
            for (int j = 0; j < projections.length; j++) {
                RiskEngine.ExpiryProjection projection = projections[j];
                if (risk.isExpiryRisk(projection)) {
                    expiries[expiryCount++] = new RiskFlag(item.getItemId(),
                            projection.batch.getBatchId(), RiskFlag.TYPE_EXPIRY,
                            risk.expiryUrgency(projection),
                            expiryReason(item, projection));
                }
            }

            // Already expired: reported as waste to be written off, not as a forecast.
            int[] indexes = batches.indexesForItem(item.getItemId());
            for (int j = 0; j < indexes.length; j++) {
                Batch batch = batches.batches()[indexes[j]];
                if (!batch.isDepleted() && batch.isExpired(today)) {
                    int daysExpired = -batch.daysLeft(today);
                    expired[expiredCount++] = new RiskFlag(item.getItemId(), batch.getBatchId(),
                            RiskFlag.TYPE_EXPIRED, daysExpired,
                            "Expired " + daysExpired + " day" + (daysExpired == 1 ? "" : "s")
                                    + " ago; " + item.describeQuantity(batch.getQtyRemaining())
                                    + " must be written off.");
                }
            }
        }

        stockouts = trim(stockouts, stockoutCount);
        expiries = trim(expiries, expiryCount);
        expired = trim(expired, expiredCount);

        sortByUrgency(stockouts);
        sortByUrgency(expiries);
        sortByUrgency(expired);

        RiskFlag[] all = new RiskFlag[stockouts.length + expiries.length + expired.length];
        int n = 0;
        for (int i = 0; i < stockouts.length; i++) {
            all[n++] = stockouts[i];
        }
        for (int i = 0; i < expiries.length; i++) {
            all[n++] = expiries[i];
        }
        for (int i = 0; i < expired.length; i++) {
            all[n++] = expired[i];
        }
        return all;
    }

    /** Counts for the summary cards, so the page never counts for itself. */
    public int countOfType(RiskFlag[] flags, String type) {
        int n = 0;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i].getType().equals(type)) {
                n++;
            }
        }
        return n;
    }

    // --------------------------------------------------------------- totals

    /** Total stock value at cost across every item. */
    public double totalStockValueAtCost(ItemAnalysis[] analyses) {
        double total = 0;
        for (int i = 0; i < analyses.length; i++) {
            total += analyses[i].stockValueAtCost;
        }
        return total;
    }

    /** Total stock value at selling price across every item. */
    public double totalStockValueAtPrice(ItemAnalysis[] analyses) {
        double total = 0;
        for (int i = 0; i < analyses.length; i++) {
            total += analyses[i].stockValueAtPrice;
        }
        return total;
    }

    /** Value already lost to waste inside the current sales window. */
    public double wasteValueInWindow() {
        return waste.valueLostInWindow(today(), settings.getSalesWindowDays());
    }

    // -------------------------------------------------------------- helpers

    private String stockoutReason(ItemAnalysis analysis) {
        String cover = analysis.neverRunsOut()
                ? "no sales recorded"
                : NumberText.quantity(analysis.daysOfCover) + " days of cover";
        return "Only " + cover + " and a delivery takes " + analysis.item.getLeadTimeDays()
                + " day" + (analysis.item.getLeadTimeDays() == 1 ? "" : "s")
                + " plus a " + settings.getSafetyMarginDays() + "-day safety margin.";
    }

    private static String expiryReason(StockItem item, RiskEngine.ExpiryProjection projection) {
        return item.describeQuantity(projection.projectedLeftover)
                + " of " + projection.batch.getBatchId() + " (expires in " + projection.daysLeft
                + " day" + (projection.daysLeft == 1 ? "" : "s") + ") would still be unsold at "
                + "the current sales rate.";
    }

    /** Insertion sort, descending urgency, ties broken by stock value at cost. */
    private void sortByUrgency(RiskFlag[] flags) {
        for (int i = 1; i < flags.length; i++) {
            RiskFlag key = flags[i];
            int j = i - 1;
            while (j >= 0 && comesBefore(key, flags[j])) {
                flags[j + 1] = flags[j];
                j--;
            }
            flags[j + 1] = key;
        }
    }

    private boolean comesBefore(RiskFlag a, RiskFlag b) {
        if (a.getUrgency() != b.getUrgency()) {
            return a.getUrgency() > b.getUrgency();
        }
        return valueOf(a.getItemId()) > valueOf(b.getItemId());
    }

    private double valueOf(String itemId) {
        StockItem item = items.findById(itemId);
        return (item == null) ? 0 : batches.stockOf(itemId) * item.getUnitCost();
    }

    private static RiskFlag[] trim(RiskFlag[] flags, int count) {
        if (count == flags.length) {
            return flags;
        }
        RiskFlag[] trimmed = new RiskFlag[count];
        for (int i = 0; i < count; i++) {
            trimmed[i] = flags[i];
        }
        return trimmed;
    }
}
