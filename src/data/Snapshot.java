package data;

import model.ActivityLog;
import model.Batch;
import model.SaleRecord;
import model.Season;
import model.Settings;
import model.StockItem;
import model.WasteRecord;
import service.ActivityLogStore;
import store.BatchStore;
import store.ItemStore;
import store.SaleStore;
import store.SeasonStore;
import store.WasteStore;

/**
 * A copy of everything a change can touch, so a failed save can be undone.
 *
 * <p>A mutation is applied in memory first and written to disk second. If the write
 * fails, the running server would hold data the files do not have. This snapshot is
 * taken immediately before the save; on failure the {@link #restore()} puts memory back
 * to exactly what the files still contain, so the server and the disk agree again.</p>
 *
 * <p>The copy is deep for every object that can change in place - batches lose quantity,
 * items can be edited, and a season's month range can be moved - and shallow only for the
 * records that are never modified, such as sale and waste entries, which are only ever
 * added or removed. Copying those deeply would be wasted work without changing the
 * outcome.</p>
 *
 * <p>Seasons deserve a note, because they are shared: an item does not own its season, it
 * holds a reference to the object in {@link SeasonStore}. Restoring the store's seasons
 * from copies is therefore only half the job - the restored items must be pointed at the
 * restored season objects too, or they would keep referring to the very seasons whose
 * months were changed. {@link #replaceItems()} resolves each item's season again by name
 * for exactly that reason.</p>
 *
 * <p>Settings are held as their saved text: that field set is already the exact set that
 * is written to settings.txt, so re-parsing it is the most faithful way to restore it
 * and cannot drift if a field is added later.</p>
 *
 * <p>Not {@code final}, for one reason: {@code ApiCheck} overrides {@link #restore()} to
 * prove what the API says when a restore fails. {@link #restore()} itself cannot fail
 * half way (see its own note), so without a seam that failure could only be imagined. It
 * is the seam the route already calls through, not a hook added for the test, and nothing
 * in the program subclasses this class.</p>
 */
public class Snapshot {

    private final ItemStore items;
    private final BatchStore batches;
    private final SaleStore sales;
    private final WasteStore waste;
    private final SeasonStore seasons;
    private final ActivityLogStore activity;
    private final Settings settings;

    private final StockItem[] itemCopy;
    private final int itemCount;
    private final Batch[] batchCopy;
    private final int batchCount;
    private final SaleRecord[] saleCopy;
    private final int saleCount;
    private final WasteRecord[] wasteCopy;
    private final int wasteCount;
    private final Season[] seasonCopy;
    private final int seasonCount;
    private final ActivityLog[] activityCopy;
    private final int activityCount;
    private final Settings settingsCopy;

    /**
     * Captures the current state. Cheap enough to take before every change to a café's
     * inventory: the stores hold hundreds of records, not millions.
     */
    public Snapshot(ItemStore items, BatchStore batches, SaleStore sales, WasteStore waste,
                    SeasonStore seasons, ActivityLogStore activity, Settings settings) {
        this.items = items;
        this.batches = batches;
        this.sales = sales;
        this.waste = waste;
        this.seasons = seasons;
        this.activity = activity;
        this.settings = settings;

        this.itemCount = items.count();
        this.itemCopy = new StockItem[itemCount];
        for (int i = 0; i < itemCount; i++) {
            itemCopy[i] = items.items()[i] == null ? null : items.items()[i].copy();
        }

        this.batchCount = batches.count();
        this.batchCopy = new Batch[batchCount];
        for (int i = 0; i < batchCount; i++) {
            batchCopy[i] = batches.batches()[i] == null ? null : batches.batches()[i].copy();
        }

        this.saleCount = sales.count();
        this.saleCopy = new SaleRecord[saleCount];
        for (int i = 0; i < saleCount; i++) {
            saleCopy[i] = sales.sales()[i];
        }

        this.wasteCount = waste.count();
        this.wasteCopy = new WasteRecord[wasteCount];
        for (int i = 0; i < wasteCount; i++) {
            wasteCopy[i] = waste.waste()[i];
        }

        this.seasonCount = seasons.count();
        this.seasonCopy = new Season[seasonCount];
        for (int i = 0; i < seasonCount; i++) {
            // Deep copy: setMonths changes a season in place, so holding the same object
            // would record the new months rather than the ones the files still have.
            seasonCopy[i] = seasons.seasons()[i] == null ? null : seasons.seasons()[i].copy();
        }

        this.activityCount = activity.count();
        this.activityCopy = new ActivityLog[activityCount];
        ActivityLog[] entries = activity.entries();
        for (int i = 0; i < activityCount; i++) {
            activityCopy[i] = entries[i];
        }

        this.settingsCopy = new Settings();
        this.settingsCopy.copySavedValuesFrom(settings);
    }

    /**
     * Puts every store back to the captured state.
     *
     * <p>Restores counts as well as contents, so a record added by the failed change is
     * removed and one removed by it is put back. The objects themselves are the copies,
     * which is what makes a rolled-back batch show its original quantity again.</p>
     *
     * <p>Seasons are restored <em>first</em>, because items are re-pointed at the restored
     * season objects while they are being re-added. Doing it the other way round would
     * leave every seasonal item referring to a season that is no longer in the store.</p>
     *
     * <p><strong>This method does not fail half way.</strong> Every step is either a
     * {@code clear()} or an {@code add()} of a copy taken from a store that already held
     * the object: the store has exactly the capacity it had when the copy was taken, the
     * copy is non-null by construction, and a copy cannot be invalid because the object it
     * came from was valid. Nothing here parses text, so no stored value can be rejected
     * and {@code copySavedValuesFrom} of an in-memory {@link Settings} cannot throw. That
     * matters more than it looks: a restore that stopped half way would leave the running
     * server describing a mixture of the failed change and the state before it, while the
     * files still hold the last saved data - and nothing in the program would notice.
     * {@link #requireRestored(String, int, int)} therefore counts what actually went back in
     * and refuses to call the job done if a store quietly took fewer records than it was
     * given. {@code PersistenceCheck} proves normal restores still agree with the files.</p>
     */
    public void restore() {
        replaceSeasons();
        requireRestored("season", seasons.count(), seasonCount);
        replaceItems();
        requireRestored("item", items.count(), itemCount);
        replaceBatches();
        requireRestored("batch", batches.count(), batchCount);
        replaceSales();
        requireRestored("sale", sales.count(), saleCount);
        replaceWaste();
        requireRestored("waste", waste.count(), wasteCount);
        replaceActivity();
        requireRestored("activity", activity.count(), activityCount);
        settings.copySavedValuesFrom(settingsCopy);
    }

    /**
     * Fails loudly if a store refused a record during a restore.
     *
     * <p>{@code add} answers {@code false} rather than throwing when the array is full, so
     * without this an under-restored store would go unnoticed. It is an
     * {@link IllegalStateException} on purpose: this is a broken invariant inside the
     * server, not a bad request, and the caller must not be told the rollback worked.</p>
     */
    private static void requireRestored(String what, int restored, int expected) {
        if (restored != expected) {
            throw new IllegalStateException("Only " + restored + " of " + expected + " " + what
                    + " records could be put back; that store no longer holds what it held"
                    + " before the change.");
        }
    }

    /**
     * Re-adds the items, each pointing at the restored season object rather than the
     * one that was in the store when the failed change ran.
     *
     * <p>An item holds a {@link Season} by reference, not by name. A copied item would
     * otherwise keep the stale reference, so a season whose months were changed and then
     * rolled back would leave items reading the changed range - the rollback would look
     * complete while seasonal demand stayed wrong. The name is the key, so resolving it
     * again is enough.</p>
     */
    private void replaceItems() {
        items.clear();
        for (int i = 0; i < itemCount; i++) {
            if (itemCopy[i] != null) {
                StockItem restored = itemCopy[i].copy();
                if (restored.getSeason() != null) {
                    restored.setSeason(seasons.findByName(restored.getSeason().getName()));
                }
                items.add(restored);
            }
        }
    }

    private void replaceBatches() {
        batches.clear();
        for (int i = 0; i < batchCount; i++) {
            if (batchCopy[i] != null) {
                batches.add(batchCopy[i].copy());
            }
        }
    }

    private void replaceSales() {
        sales.clear();
        for (int i = 0; i < saleCount; i++) {
            if (saleCopy[i] != null) {
                sales.add(saleCopy[i]);
            }
        }
    }

    private void replaceWaste() {
        waste.clear();
        for (int i = 0; i < wasteCount; i++) {
            if (wasteCopy[i] != null) {
                waste.add(wasteCopy[i]);
            }
        }
    }

    private void replaceSeasons() {
        seasons.clear();
        for (int i = 0; i < seasonCount; i++) {
            if (seasonCopy[i] != null) {
                seasons.add(seasonCopy[i]);
            }
        }
    }

    private void replaceActivity() {
        activity.clear();
        for (int i = 0; i < activityCount; i++) {
            if (activityCopy[i] != null) {
                activity.add(activityCopy[i]);
            }
        }
    }

    /** Counts captured, for a check program to compare against after a restore. */
    public String countsText() {
        return itemCount + "/" + batchCount + "/" + saleCount + "/" + wasteCount + "/"
                + seasonCount + "/" + activityCount;
    }
}
