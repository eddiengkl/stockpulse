package service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import model.Batch;
import model.Settings;
import model.StockItem;
import store.BatchStore;
import store.SaleStore;

/**
 * The arithmetic behind the risk report: observed sales velocity, seasonal demand,
 * days of cover, reorder point and batch-by-batch expiry projection.
 *
 * <p>Everything here is deterministic and unit-free. It reads model objects and returns
 * numbers; it never changes stored data, and it never calls the AI service. The AI
 * advisor is given the results of these methods, not the other way round.</p>
 *
 * <p>Velocity rule, used everywhere: sum the units sold in the last
 * {@code settings.salesWindowDays} calendar days and divide by
 * {@code min(windowDays, calendar days since the item was first stocked)}. Days with
 * no sale count as zero, so the divisor includes them; the window is never reset by a
 * later delivery. This is <em>observed sales velocity</em> - it cannot reveal demand
 * that was lost while the item was out of stock.</p>
 */
public class RiskEngine {

    private final ItemLookup items;
    private final BatchStore batches;
    private final SaleStore sales;
    private final Settings settings;

    /** Small functional-ish interface so the engine does not depend on ItemStore directly. */
    @FunctionalInterface
    public interface ItemLookup {
        StockItem byId(String itemId);
    }

    public RiskEngine(ItemLookup items, BatchStore batches, SaleStore sales, Settings settings) {
        this.items = items;
        this.batches = batches;
        this.sales = sales;
        this.settings = settings;
    }

    // ------------------------------------------------------------ velocity

    /**
     * Calendar days from the earliest evidence of this item being stocked, up to
     * {@code today}, counting today as day 1.
     *
     * <p>The earliest evidence is the earlier of the first recorded sale and the oldest
     * batch received date. A later delivery does not reset it.</p>
     *
     * @return at least 1
     */
    public int daysSinceFirstStocked(String itemId, LocalDate today) {
        LocalDate earliest = sales.firstSaleDate(itemId);

        int[] indexes = batches.indexesForItem(itemId);
        for (int i = 0; i < indexes.length; i++) {
            LocalDate received = batches.batches()[indexes[i]].getReceivedDate();
            if (earliest == null || received.isBefore(earliest)) {
                earliest = received;
            }
        }
        if (earliest == null) {
            return 1;
        }
        long days = ChronoUnit.DAYS.between(earliest, today) + 1;
        return (int) Math.max(1, days);
    }

    /** The divisor actually used by the velocity rule. */
    public int velocityDivisor(String itemId, LocalDate today) {
        int window = settings.getSalesWindowDays();
        int daysStocked = daysSinceFirstStocked(itemId, today);
        return Math.max(1, Math.min(window, daysStocked));
    }

    /**
     * Units per day, observed. Zero-sale days inside the divisor count as zero.
     *
     * @return 0 when the item has never sold
     */
    public double observedVelocity(String itemId, LocalDate today) {
        double total = sales.unitsSold(itemId, today, settings.getSalesWindowDays());
        return total / velocityDivisor(itemId, today);
    }

    /**
     * Velocity adjusted for the active season's item multiplier. Returns the plain
     * observed velocity when the item is not seasonal or no season is active.
     */
    public double seasonalVelocity(StockItem item, double observedVelocity) {
        model.Season active = settings.resolveActiveSeason(
                currentSeasons(), currentSeasonCount());
        return observedVelocity * item.seasonalMultiplierFor(active);
    }

    // Seasons are supplied by the service layer through these two hooks so the engine
    // keeps a narrow dependency surface.
    private model.Season[] seasonArray = new model.Season[0];
    private int seasonCount = 0;

    /** Supplies the season array for seasonal velocity. Called once when wiring. */
    public void useSeasons(model.Season[] seasons, int count) {
        this.seasonArray = (seasons == null) ? new model.Season[0] : seasons;
        this.seasonCount = count;
    }

    private model.Season[] currentSeasons() {
        return seasonArray;
    }

    private int currentSeasonCount() {
        return seasonCount;
    }

    // -------------------------------------------------------- stockout rules

    /** Days of cover: stock on hand divided by seasonal daily demand. */
    public double daysOfCover(double stock, double seasonalVelocity) {
        if (seasonalVelocity <= 0) {
            return Double.POSITIVE_INFINITY;   // nothing sells, so it never runs out
        }
        return stock / seasonalVelocity;
    }

    /** Reorder point: demand for the lead time plus the safety margin. */
    public double reorderPoint(double seasonalVelocity, int leadTimeDays) {
        return seasonalVelocity * settings.coverDaysFor(leadTimeDays);
    }

    /**
     * Whether days of cover fall to or below the lead time plus the safety margin, i.e.
     * stock would run out before a new delivery could arrive.
     */
    public boolean isStockoutRisk(double daysOfCover, int leadTimeDays) {
        return daysOfCover <= settings.coverDaysFor(leadTimeDays);
    }

    /**
     * How much worse than the cover threshold this item is, in days.
     *
     * <p>Returned as {@code threshold - daysOfCover}:
     * <ul>
     *   <li><strong>positive</strong> means the item is <em>at risk</em>; a bigger value
     *       means it runs out sooner, so sorting descending puts the worst first;</li>
     *   <li>zero means it runs out exactly when a delivery could arrive;</li>
     *   <li><strong>negative</strong> means there is margin left, and a more negative
     *       value means more comfortable stock.</li>
     * </ul>
     * Only meaningful for items already flagged by {@link #isStockoutRisk}.</p>
     */
    public double stockoutUrgency(double daysOfCover, int leadTimeDays) {
        return settings.coverDaysFor(leadTimeDays) - daysOfCover;
    }

    /**
     * Reorder quantity: cover the lead time, the safety margin and the reorder horizon,
     * then round up to whole supplier packs.
     *
     * @return 0 when no order is needed
     */
    public double reorderQuantity(double stock, double seasonalVelocity, int leadTimeDays, int packSize) {
        double target = seasonalVelocity
                * (settings.coverDaysFor(leadTimeDays) + settings.getReorderHorizonDays());
        double needed = target - stock;
        if (needed <= 0) {
            return 0;
        }
        int packs = (int) Math.ceil(needed / packSize);
        return (double) packs * packSize;
    }

    // ---------------------------------------------------------- expiry rules

    /**
     * Per-batch expiry projection, simulated forward in time.
     *
     * <p>Batches are walked in expiry order while a simulated shelf is drawn down by
     * demand. At each expiry date anything left in the batches expiring up to that date
     * is <strong>removed</strong> (it becomes waste), and only then is the next batch
     * projected. Simply subtracting all demand from cumulative stock would let stock
     * that already expired feed later batches and overstate their leftover.</p>
     *
     * <p>Worked example with demand 2/day: 12 units expiring in 3 days and another 12 in
     * 9 days. By day 3, 6 units have sold, so 6 expire on day 3. Demand over days 4-9 is
     * 12 units, which exactly clears the second batch, so its leftover is 0, not 6.</p>
     *
     * @return one result per non-depleted, non-expired batch of the item, in expiry order
     */
    public ExpiryProjection[] projectExpiry(String itemId, double seasonalVelocity, LocalDate today) {
        int[] indexes = batches.indexesForItem(itemId);
        Batch[] order = new Batch[indexes.length];
        int n = 0;
        for (int i = 0; i < indexes.length; i++) {
            Batch batch = batches.batches()[indexes[i]];
            if (!batch.isDepleted() && !batch.isExpired(today)) {
                order[n++] = batch;
            }
        }
        // Insertion sort by expiry date; batches with no expiry go last. n is tiny.
        for (int i = 1; i < n; i++) {
            Batch key = order[i];
            int j = i - 1;
            while (j >= 0 && expirySortKey(order[j]) > expirySortKey(key)) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = key;
        }

        ExpiryProjection[] result = new ExpiryProjection[n];

        // Simulated shelf, in expiry order: shelf[0] is the soonest-expiring batch.
        // `daysConsumed` records how far the simulation has advanced, so demand between
        // two expiry dates is counted once and only once.
        double[] shelf = new double[n];
        for (int i = 0; i < n; i++) {
            shelf[i] = order[i].getQtyRemaining();
        }
        double daysConsumed = 0;

        for (int i = 0; i < n; i++) {
            int daysLeft = order[i].daysLeft(today);

            if (daysLeft == Integer.MAX_VALUE) {
                // No expiry date: this stock cannot go off, so there is nothing to project.
                result[i] = new ExpiryProjection(order[i], daysLeft, 0, 0);
                continue;
            }

            // What is on the shelf when this batch's expiry date arrives: the stock of
            // batches 0..i - those expiring on or before this date - less everything sold
            // up to that date. Later batches are excluded: they have not been received
            // into the risk yet, and counting them would overstate this expiry.
            double stockAtExpiry = shelfQty(shelf, 0, i);

            // 1. Sell from the front of the shelf up to the expiry date.
            double demand = seasonalVelocity * Math.max(0, (double) daysLeft - daysConsumed);
            consumeFromFront(shelf, demand);
            daysConsumed = Math.max(daysConsumed, daysLeft);

            // 2. Anything still there on the expiry date is thrown away. Leaving it on
            //    the shelf would let expired stock feed later batches, which is how the
            //    earlier version overstated a later batch's leftover.
            double leftoverHere = shelfQty(shelf, 0, i);
            for (int j = 0; j <= i; j++) {
                shelf[j] = 0;
            }

            result[i] = new ExpiryProjection(order[i], daysLeft, stockAtExpiry, leftoverHere);
        }
        return result;
    }

    /**
     * Stock held in shelf positions {@code from..to} inclusive. Callers pass the range
     * of batches that have expired by the date being projected, so later batches do not
     * leak into an earlier batch's figures.
     */
    private static double shelfQty(double[] shelf, int from, int to) {
        double total = 0;
        for (int i = from; i <= to && i < shelf.length; i++) {
            total += shelf[i];
        }
        return total;
    }

    /**
     * Sells {@code demand} from the soonest-expiring end of the shelf first, which is the
     * same order a real sale would take stock.
     */
    private static void consumeFromFront(double[] shelf, double demand) {
        double remaining = demand;
        for (int i = 0; i < shelf.length && remaining > 0.0000001; i++) {
            double taken = Math.min(remaining, shelf[i]);
            shelf[i] -= taken;
            remaining -= taken;
        }
    }

    private static long expirySortKey(Batch batch) {
        return (batch.getExpiryDate() == null) ? Long.MAX_VALUE : batch.getExpiryDate().toEpochDay();
    }

    /**
     * Share of a batch that would still be unsold on its expiry date, used as the
     * expiry urgency. 0 means nothing is left over, 1 means all of it is.
     */
    public double expiryUrgency(ExpiryProjection projection) {
        if (projection.batch.getQtyRemaining() <= 0) {
            return 0;
        }
        return projection.projectedLeftover / projection.batch.getQtyRemaining();
    }

    /** True when a batch will still have stock left on its expiry date. */
    public boolean isExpiryRisk(ExpiryProjection projection) {
        return projection.projectedLeftover > 0.0000001;
    }

    // ------------------------------------------------------------ snapshots

    /** One batch's projection, computed by {@link #projectExpiry}. */
    public static final class ExpiryProjection {
        public final Batch batch;
        public final int daysLeft;
        /** Total shelf stock (this batch and everything expiring sooner) on the expiry date. */
        public final double stockAtExpiry;
        /** What would still be unsold on the expiry date and therefore become waste. */
        public final double projectedLeftover;

        ExpiryProjection(Batch batch, int daysLeft, double stockAtExpiry, double projectedLeftover) {
            this.batch = batch;
            this.daysLeft = daysLeft;
            this.stockAtExpiry = stockAtExpiry;
            this.projectedLeftover = projectedLeftover;
        }
    }

    /** Convenience: the settings this engine was built with. */
    public Settings settings() {
        return settings;
    }

    /** Convenience: look an item up through the same lookup the engine uses. */
    public StockItem item(String itemId) {
        return items.byId(itemId);
    }
}
