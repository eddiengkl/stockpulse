package data;

import exception.DataFileException;
import exception.InvalidSeasonException;
import exception.StorageFullException;
import model.Settings;
import service.ActivityLogStore;
import store.BatchStore;
import store.ItemStore;
import store.SaleStore;
import store.SeasonStore;
import store.WasteStore;

/**
 * The one call the program makes at startup: get the stores into a valid state.
 *
 * <p>Three cases, in this order:</p>
 * <ol>
 *   <li><strong>First run</strong> - no data files exist, so the sample café dataset is
 *       written and then loaded back from disk.</li>
 *   <li><strong>Normal run</strong> - the files are loaded.</li>
 *   <li><strong>Damaged file</strong> - loading throws {@link DataFileException} naming
 *       the file and line. Nothing is written, so the damaged file is left exactly as it
 *       is for the user to inspect.</li>
 * </ol>
 *
 * <p>The caller is expected to report a {@link DataFileException} and stop the program.
 * Continuing with a half-loaded dataset and then saving would overwrite good records with
 * a partial set, which is the data loss this phase is designed to prevent.</p>
 */
public final class DataBootstrap {

    private DataBootstrap() {
    }

    /**
     * Loads the stores, seeding the sample data first on a first run.
     *
     * @return true when the sample data was created on this run
     * @throws DataFileException     when a data file is unreadable; nothing is written
     * @throws StorageFullException  when a file holds more records than the store allows
     * @throws InvalidSeasonException when the seed data itself is misconfigured
     */
    public static boolean loadOrSeed(DataStore store, ItemStore items, BatchStore batches,
                                     SaleStore sales, WasteStore waste, SeasonStore seasons,
                                     ActivityLogStore activity, Settings settings)
            throws DataFileException, StorageFullException, InvalidSeasonException {
        if (store.isFirstRun()) {
            // Build the sample set in memory, write it, then throw the in-memory objects
            // away and reload. The first run therefore also proves the save/load round
            // trip instead of trusting objects that were never written.
            SampleData.seed(store, items, batches, sales, waste, seasons, activity, settings);
            clearAll(items, batches, sales, waste, seasons, activity);
            store.loadAll(items, batches, sales, waste, seasons, activity);
            return true;
        }
        store.loadAll(items, batches, sales, waste, seasons, activity);
        return false;
    }

    private static void clearAll(ItemStore items, BatchStore batches, SaleStore sales,
                                 WasteStore waste, SeasonStore seasons,
                                 ActivityLogStore activity) {
        items.clear();
        batches.clear();
        sales.clear();
        waste.clear();
        seasons.clear();
        activity.clear();
    }
}
