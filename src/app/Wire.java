package app;

import java.nio.file.Path;

import data.DataBootstrap;
import data.DataFiles;
import data.DataStore;
import data.Snapshot;
import ai.AdvisorConfig;
import ai.AdvisorService;
import ai.GeminiClient;
import exception.DataFileException;
import exception.InvalidSeasonException;
import exception.StorageFullException;
import model.Settings;
import server.Routes;
import server.WriteGuard;
import service.ActivityLogStore;
import service.DashboardService;
import service.InventoryService;
import service.ReadService;
import store.BatchStore;
import store.ItemStore;
import store.SaleStore;
import store.SeasonStore;
import store.WasteStore;

/**
 * Builds the whole object graph: stores, services, routes, and the save callback.
 *
 * <p>It exists as its own class so that {@code StockPulseApp.main} and the check program
 * build the system the same way. If the wiring were duplicated in two places, a check
 * could pass against a configuration the real program never uses, which is worse than no
 * check at all.</p>
 *
 * <p>This class does not start a server and does not call {@code System.exit}: it either
 * returns a wired system or throws, so the caller decides how to report a failure.</p>
 */
public final class Wire {

    public final Settings settings;
    public final ItemStore items;
    public final BatchStore batches;
    public final SaleStore sales;
    public final WasteStore waste;
    public final SeasonStore seasons;
    public final ActivityLogStore activity;
    public final DataStore dataStore;
    public final InventoryService inventory;
    public final DashboardService dashboard;
    public final ReadService read;
    public final Routes routes;

    /**
     * Blocks further writes after a failed save. Exposed so the startup banner and the
     * check program can report its state.
     */
    public final WriteGuard guard;

    /** The AI advisor, and where its key came from. The key itself is never exposed. */
    public final ai.AdvisorService advisor;
    public final ai.AdvisorConfig advisorConfig;

    /** True when the sample data had to be created because no data files existed. */
    public final boolean seeded;

    private Wire(Path dataDirectory) throws DataFileException, StorageFullException,
            InvalidSeasonException {
        this.settings = new Settings();
        this.items = new ItemStore();
        this.batches = new BatchStore();
        this.sales = new SaleStore();
        this.waste = new WasteStore();
        this.seasons = new SeasonStore();
        this.activity = new ActivityLogStore();
        this.dataStore = new DataStore(dataDirectory, settings);

        this.seeded = DataBootstrap.loadOrSeed(dataStore, items, batches, sales, waste, seasons,
                activity, settings);

        this.inventory = new InventoryService(items, batches, sales, waste, settings, activity);
        inventory.useSeasons(seasons.seasons(), seasons.count());

        this.dashboard = new DashboardService(items, batches, seasons, waste, settings,
                inventory.risk(), inventory.allocator());

        this.read = new ReadService(dashboard, items::findById, batches, sales::recentForItem,
                seasons, settings, activity::recent, inventory);

        // The route table gets the save callback twice: once behind the guard, which
        // records a failure and refuses later writes, and once as a snapshot factory,
        // which lets a failed save be undone. See Routes.change().
        this.guard = new WriteGuard(
                () -> dataStore.saveAll(items, batches, sales, waste, seasons, activity));

        // The advisor reads the same services as everything else and decides nothing: it
        // formats figures Java already computed. With no key it is still constructed, so the
        // route can report "no key configured" rather than 404.
        this.advisorConfig = AdvisorConfig.load();
        this.advisor = new AdvisorService(read, advisorConfig,
                advisorConfig.newClient());

        this.routes = new Routes(read, inventory, seasons, settings, guard,
                () -> new Snapshot(items, batches, sales, waste, seasons, activity, settings),
                advisor);
    }

    /**
     * Loads or seeds the data in the given folder and wires everything up.
     *
     * @param dataDirectory folder holding the seven text files
     * @throws DataFileException    when a file is missing or holds a bad line
     * @throws StorageFullException when a file holds more records than a store allows
     * @throws InvalidSeasonException when the season data cannot be built
     */
    public static Wire build(Path dataDirectory) throws DataFileException,
            StorageFullException, InvalidSeasonException {
        if (dataDirectory == null) {
            throw new IllegalArgumentException("A data directory is required.");
        }
        return new Wire(dataDirectory);
    }

    /** The default folder, relative to where the program was started. */
    public static Wire buildInDefaultDirectory() throws DataFileException,
            StorageFullException, InvalidSeasonException {
        return build(Path.of(DataFiles.DATA_DIR));
    }
}
