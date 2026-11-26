package service;

import java.time.LocalDate;

import exception.ItemNotFoundException;
import model.ActivityLog;
import model.Batch;
import model.RiskFlag;
import model.SaleRecord;
import model.Season;
import model.Settings;
import model.StockItem;
import store.BatchStore;
import store.SeasonStore;

/**
 * Assembles the data each read route returns.
 *
 * <p>This is the layer that decides <em>what the page is told</em>: which fields, in what
 * order, with the numbers already computed. It holds no HTTP knowledge - it does not set
 * a status code or write a header - so the same data could be used by the AI advisor's
 * fact sheet without going through the web server.</p>
 */
public class ReadService {

    /** How many entries the activity panel shows. */
    public static final int ACTIVITY_LIMIT = 40;

    /** How many days of sales history the item detail page shows. */
    public static final int HISTORY_DAYS = 14;

    private final DashboardService dashboard;
    private final ItemStoreLike items;
    private final BatchStore batches;
    private final SaleStoreLike sales;
    private final SeasonStore seasons;
    private final Settings settings;
    private final ActivityLogStoreLike activity;
    private final InventoryService inventory;

    /**
     * Narrow views of the stores, so this class cannot mutate anything even by mistake:
     * there is no {@code add}, {@code remove} or {@code clear} in any of these.
     */
    @FunctionalInterface
    public interface ItemStoreLike {
        StockItem findById(String itemId);
    }

    @FunctionalInterface
    public interface SaleStoreLike {
        SaleRecord[] recentForItem(String itemId, int limit);
    }

    @FunctionalInterface
    public interface ActivityLogStoreLike {
        ActivityLog[] recent(int limit);
    }

    public ReadService(DashboardService dashboard, ItemStoreLike items, BatchStore batches,
                       SaleStoreLike sales, SeasonStore seasons, Settings settings,
                       ActivityLogStoreLike activity, InventoryService inventory) {
        this.dashboard = dashboard;
        this.items = items;
        this.batches = batches;
        this.sales = sales;
        this.seasons = seasons;
        this.settings = settings;
        this.activity = activity;
        this.inventory = inventory;
    }

    public DashboardService dashboard() {
        return dashboard;
    }

    public LocalDate today() {
        return settings.effectiveDate();
    }

    /**
     * Resolves an item or throws, so every item route reports the same 404.
     *
     * @throws ItemNotFoundException when the id is unknown
     */
    public StockItem requireItem(String itemId) throws ItemNotFoundException {
        StockItem item = items.findById(itemId);
        if (item == null) {
            throw new ItemNotFoundException(itemId, "Item");
        }
        return item;
    }

    /** The batches of an item, newest id first, for the item detail page. */
    public Batch[] batchesOf(String itemId) {
        int[] indexes = batches.indexesForItem(itemId);
        Batch[] result = new Batch[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            result[i] = batches.batches()[indexes[i]];
        }
        return result;
    }

    /** The most recent sales of an item, newest first. */
    public SaleRecord[] recentSales(String itemId) {
        return sales.recentForItem(itemId, HISTORY_DAYS);
    }

    /** The most recent activity lines, newest first. */
    public ActivityLog[] recentActivity() {
        return activity.recent(ACTIVITY_LIMIT);
    }

    public Season[] seasons() {
        Season[] all = seasons.seasons();
        Season[] copy = new Season[seasons.count()];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = all[i];
        }
        return copy;
    }

    /** The season currently in force, or null when none matches. */
    public Season activeSeason() {
        return settings.resolveActiveSeason(seasons.seasons(), seasons.count());
    }

    public Settings settings() {
        return settings;
    }

    /** All flags, in the ranked order the dashboard displays. */
    public RiskFlag[] flags() {
        return dashboard.flags();
    }

    public ItemAnalysis[] analyses() {
        return dashboard.analyseAll();
    }

    public InventoryService inventory() {
        return inventory;
    }
}
