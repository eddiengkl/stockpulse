package data;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import exception.DataFileException;
import exception.StorageFullException;
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
 * Loads every store from the text files, and writes them back.
 *
 * <p>This class is the only place that touches the disk. Every file is read line by line
 * straight into a temporary array - no collection ever holds the lines or the records.</p>
 *
 * <p>Three safety rules shape the design:</p>
 * <ol>
 *   <li><strong>Load fully, then commit.</strong> Every file is parsed into a temporary
 *       array first, and settings into a temporary {@link Settings}. If any line fails,
 *       {@link #loadAll} throws and neither the stores nor the live settings are touched -
 *       nothing is ever half-loaded.</li>
 *   <li><strong>A missing file is an error.</strong> Seeding only happens when
 *       <em>no</em> data file exists, so a partly missing folder means something was
 *       deleted or a save was interrupted. Loading it as empty data and saving would wipe
 *       the records the surviving files still hold.</li>
 *   <li><strong>A save is staged, then reversed if it fails.</strong> All seven files are
 *       written under a staging name first and only then moved into place, each keeping a
 *       backup copy of the version it replaces. A failure while swapping puts the files
 *       already replaced back, so a failed save leaves the folder as it was. If even that
 *       reverse copy fails, the error says the dataset is partly written and needs
 *       recovery instead of claiming a clean rollback - see {@link #saveAll}.</li>
 *   <li><strong>A save that could not be finished is never loaded.</strong> A marker file
 *       exists for as long as a save is under way. It is removed once the save is whole -
 *       and also when the save failed but was <em>fully</em> reversed, because the folder
 *       is then exactly what the previous, successful save left and is safe to load. What
 *       keeps the marker is the case where reversing failed: the files may mix two
 *       moments, so the loader refuses rather than loading whichever mixture is on disk -
 *       see {@link #requireNoInterruptedSave}. The marker also means seeding can never
 *       start while a save is half done, which the missing-file rule above would
 *       otherwise allow.</li>
 * </ol>
 */
public class DataStore {

    private final Path directory;
    private final Settings settings;

    /**
     * Decides, per file, whether this save should fail - and whether putting the file
     * back should fail too.
     *
     * <p>There is no way to ask a real disk to fail part way through a save, so the check
     * program supplies one of these. It is the only way to reach the two outcomes that
     * matter: a save reversed cleanly, and a save that could not be reversed. The program
     * itself never sets one, and the fault can only ever make a write fail, never make it
     * succeed.</p>
     */
    @FunctionalInterface
    public interface FaultInjector {
        /** What should go wrong for the save of the given file. */
        Fault faultFor(String fileName);
    }

    /**
     * What a save left behind: the new records are on disk, but the folder is not ready to
     * be loaded.
     *
     * <p>The name is deliberately unromantic. The save did happen - every data file holds
     * the new records - so the caller must keep the change in memory instead of putting the
     * old figures back. What it cannot do is call the save finished, because something in
     * the folder will make the next start refuse it.</p>
     *
     * @param reason    what went wrong, in the same form every other failure is reported in
     * @param leftovers the recovery files that are still in the folder, in the order found
     */
    public static final class CleanupIncomplete {
        private final DataFileException reason;
        private final String leftoverList;

        private CleanupIncomplete(DataFileException reason, String leftoverList) {
            this.reason = reason;
            this.leftoverList = leftoverList;
        }

        /** What went wrong, in the same form every other failure is reported in. */
        public DataFileException reason() {
            return reason;
        }

        /** "save-in-progress.tmp, seasons.txt.bak" - what still has to be deleted. */
        public String leftovers() {
            return leftoverList;
        }
    }

    /** One file's worth of injected trouble. */
    public static final class Fault {
        /** Nothing goes wrong. */
        public static final Fault NONE = new Fault(false, false, false);

        /** The swap for this file fails. */
        public static final Fault SAVE_FAILS = new Fault(true, false, false);

        /** The swap for this file fails, and putting it back fails as well. */
        public static final Fault SAVE_AND_RESTORE_FAIL = new Fault(true, true, false);

        // Six booleans in a row are far too easy to write in the wrong order - a check
        // once set "staging fails" in the marker-delete slot and quietly tested nothing.
        // The two faults that use the newer flags are therefore named, not positional.


        /**
         * Copying the file aside before the swap fails, so the swap never starts.
         *
         * <p>This is the case that must not end in a silent restore: the copy meant to
         * be the old contents was cut short, and the file is still the version from
         * before the save. Putting that "backup" over it would be pointless at best and
         * destructive at worst.</p>
         */
        public static final Fault BACKUP_COPY_FAILS = new Fault(false, false, true);

        /**
         * The save works, but this file's backup cannot be deleted afterwards.
         *
         * <p>The new record is on disk and whole; only the copy of the old contents is
         * left. That leftover is not harmless - a start-up treats any {@code .bak} as the
         * sign of an unfinished save and refuses the folder - so this is the case where a
         * save that succeeded still has to report that a person must remove one file.</p>
         */
        public static final Fault BACKUP_DELETE_FAILS = withBackupDeleteFailing();

        /**
         * All seven files are replaced, and then the marker cannot be deleted.
         *
         * <p>The change is on disk and is not going back: the backups are already gone, so
         * there is nothing left to reverse with. What is left behind is the marker, which
         * makes the next start refuse the folder until someone deletes it.</p>
         */
        public static final Fault MARKER_DELETE_FAILS = withMarkerDeleteFailing();

        /**
         * Staging fails before any data file is touched, and then the marker cannot be
         * deleted either.
         *
         * <p>The simplest failure there is - no data file written - leaves a folder a
         * normal start refuses. Worth its own case, because the recovery instruction has to
         * name a file without hinting that any record was lost.</p>
         */
        public static final Fault MARKER_DELETE_ON_STAGING_FAILS =
                withStagingAndMarkerDeleteFailing();

        // Named builders for the faults that use the newer flags. Each one sets exactly the
        // flags its name mentions, so the order of private constructor arguments can no
        // longer turn one fault into another.
        private static Fault withBackupDeleteFailing() {
            return new Fault(false, false, false, true, false, false);
        }

        private static Fault withMarkerDeleteFailing() {
            return new Fault(false, false, false, false, true, false);
        }

        private static Fault withStagingAndMarkerDeleteFailing() {
            return new Fault(false, false, false, false, true, true);
        }

        private final boolean failSave;
        private final boolean failRestore;
        private final boolean failBackup;
        private final boolean failBackupDelete;
        private final boolean failMarkerDelete;
        private final boolean failStaging;

        private Fault(boolean failSave, boolean failRestore, boolean failBackup) {
            this(failSave, failRestore, failBackup, false, false, false);
        }

        private Fault(boolean failSave, boolean failRestore, boolean failBackup,
                      boolean failBackupDelete, boolean failMarkerDelete) {
            this(failSave, failRestore, failBackup, failBackupDelete, failMarkerDelete, false);
        }

        private Fault(boolean failSave, boolean failRestore, boolean failBackup,
                      boolean failBackupDelete, boolean failMarkerDelete, boolean failStaging) {
            this.failSave = failSave;
            this.failRestore = failRestore;
            this.failBackup = failBackup;
            this.failBackupDelete = failBackupDelete;
            this.failMarkerDelete = failMarkerDelete;
            this.failStaging = failStaging;
        }

        public boolean failsSave() {
            return failSave;
        }

        public boolean failsRestore() {
            return failRestore;
        }

        public boolean failsBackup() {
            return failBackup;
        }

        public boolean failsBackupDelete() {
            return failBackupDelete;
        }

        public boolean failsMarkerDelete() {
            return failMarkerDelete;
        }

        public boolean failsStaging() {
            return failStaging;
        }
    }

    private FaultInjector faults;

    /**
     * Installs a fault injector for the next saves. Only a check program needs this;
     * passing null restores normal behaviour.
     */
    public void injectFaults(FaultInjector faults) {
        this.faults = faults;
    }

    /** The injected fault for a file, or none. */
    private Fault faultFor(String fileName) {
        return faults == null ? Fault.NONE : faults.faultFor(fileName);
    }

    public DataStore(Path directory, Settings settings) {
        this.directory = directory;
        this.settings = settings;
    }

    public static DataStore inDefaultDirectory(Settings settings) {
        return new DataStore(Path.of(DataFiles.DATA_DIR), settings);
    }

    public Path directory() {
        return directory;
    }

    public Path pathOf(String fileName) {
        return directory.resolve(fileName);
    }

    /** Counts the data files that exist, so a partial folder can be told from a full one. */
    public int existingFileCount() {
        int found = 0;
        for (int i = 0; i < DataFiles.ALL.length; i++) {
            if (Files.isRegularFile(pathOf(DataFiles.ALL[i]))) {
                found++;
            }
        }
        return found;
    }

    /**
     * True when this is a first run: no data files exist, and nothing says a save was
     * interrupted.
     *
     * <p>The second half matters because seeding writes the sample data <em>without</em>
     * going through {@link #loadAll}, so it would never meet
     * {@link #requireNoInterruptedSave}. Without this test a folder holding only leftovers
     * - a marker, a backup, a half-copied backup, and not one data file - would look
     * empty, and the sample data would be written straight over the evidence of a save
     * someone still needs to look at.</p>
     */
    public boolean isFirstRun() {
        return existingFileCount() == 0
                && !Files.exists(pathOf(SAVE_IN_PROGRESS))
                && namesWithSuffix(BACKUP_SUFFIX).isEmpty()
                && namesWithSuffix(BACKUP_STAGING_SUFFIX).isEmpty();
    }

    // ------------------------------------------------------------------- load

    /**
     * Parses every file and fills the stores, then copies the saved settings across.
     *
     * <p>Order matters: seasons before items (an item refers to one), items before
     * batches/sales/waste (their ids are checked against it), and settings last, because
     * they are applied to a temporary object and copied in only at the very end.</p>
     *
     * @throws DataFileException    when a file is missing, unreadable, or holds a bad line
     * @throws StorageFullException when a file holds more records than its store allows
     */
    public void loadAll(ItemStore items, BatchStore batches, SaleStore sales,
                        WasteStore waste, SeasonStore seasons, ActivityLogStore activity)
            throws DataFileException, StorageFullException {
        DataFileReader reader = new DataFileReader(directory);

        // A folder whose last save never finished must not be read at all: whichever
        // records are on disk, they are not one moment in the shop's life.
        requireNoInterruptedSave();

        // Refuse a partial data folder before reading anything, so the message names the
        // missing file rather than reporting a confusing downstream reference error.
        requireAllFilesPresent(reader);

        // ---- seasons first: items may point at them
        final Season[] loadedSeasons = new Season[seasons.capacity()];
        final int[] seasonCount = {0};
        reader.forEachLine(DataFiles.SEASONS, (line, number) -> {
            Season season = DataParser.parseSeason(line, number, DataFiles.SEASONS);
            if (seasonCount[0] >= loadedSeasons.length) {
                throw new DataFileException(DataFiles.SEASONS, number,
                        "holds more seasons than the store allows (" + loadedSeasons.length + ")");
            }
            if (DataParser.findSeason(loadedSeasons, seasonCount[0], season.getName()) != null) {
                throw new DataFileException(DataFiles.SEASONS, number,
                        "season '" + season.getName() + "' is defined more than once");
            }
            loadedSeasons[seasonCount[0]++] = season;
        });

        // ---- items
        final StockItem[] loadedItems = new StockItem[items.capacity()];
        final int[] itemCount = {0};
        reader.forEachLine(DataFiles.ITEMS, (line, number) -> {
            StockItem item = DataParser.parseItem(line, number, DataFiles.ITEMS,
                    loadedSeasons, seasonCount[0]);
            if (itemCount[0] >= loadedItems.length) {
                throw new DataFileException(DataFiles.ITEMS, number,
                        "holds more items than the store allows (" + loadedItems.length + ")");
            }
            for (int j = 0; j < itemCount[0]; j++) {
                if (loadedItems[j].getItemId().equalsIgnoreCase(item.getItemId())) {
                    throw new DataFileException(DataFiles.ITEMS, number,
                            "item id '" + item.getItemId() + "' is used more than once");
                }
            }
            loadedItems[itemCount[0]++] = item;
        });

        // ---- batches
        final Batch[] loadedBatches = new Batch[batches.capacity()];
        final int[] batchCount = {0};
        reader.forEachLine(DataFiles.BATCHES, (line, number) -> {
            Batch batch = DataParser.parseBatch(line, number, DataFiles.BATCHES);
            if (batchCount[0] >= loadedBatches.length) {
                throw new DataFileException(DataFiles.BATCHES, number,
                        "holds more batches than the store allows (" + loadedBatches.length + ")");
            }
            // An orphan batch would silently never appear on the dashboard.
            if (!containsItemId(loadedItems, itemCount[0], batch.getItemId())) {
                throw new DataFileException(DataFiles.BATCHES, number,
                        "batch '" + batch.getBatchId() + "' refers to item '"
                                + batch.getItemId() + "', which is not in " + DataFiles.ITEMS);
            }
            for (int j = 0; j < batchCount[0]; j++) {
                if (loadedBatches[j].getBatchId().equalsIgnoreCase(batch.getBatchId())) {
                    throw new DataFileException(DataFiles.BATCHES, number,
                            "batch id '" + batch.getBatchId() + "' is used more than once");
                }
            }
            loadedBatches[batchCount[0]++] = batch;
        });

        // ---- sales, including a check that the draws describe real batches of that item
        final SaleRecord[] loadedSales = new SaleRecord[sales.capacity()];
        final int[] saleCount = {0};
        reader.forEachLine(DataFiles.SALES, (line, number) -> {
            SaleRecord sale = DataParser.parseSale(line, number, DataFiles.SALES);
            if (saleCount[0] >= loadedSales.length) {
                throw new DataFileException(DataFiles.SALES, number,
                        "holds more sales than the store allows (" + loadedSales.length + ")");
            }
            if (!containsItemId(loadedItems, itemCount[0], sale.getItemId())) {
                throw new DataFileException(DataFiles.SALES, number,
                        "sale '" + sale.getSaleId() + "' refers to item '" + sale.getItemId()
                                + "', which is not in " + DataFiles.ITEMS);
            }
            checkSaleDraws(sale, number, loadedBatches, batchCount[0]);
            loadedSales[saleCount[0]++] = sale;
        });

        // ---- waste, including a check that the batch belongs to the item
        final WasteRecord[] loadedWaste = new WasteRecord[waste.capacity()];
        final int[] wasteCount = {0};
        reader.forEachLine(DataFiles.WASTE, (line, number) -> {
            WasteRecord record = DataParser.parseWaste(line, number, DataFiles.WASTE);
            if (wasteCount[0] >= loadedWaste.length) {
                throw new DataFileException(DataFiles.WASTE, number,
                        "holds more waste records than the store allows (" + loadedWaste.length + ")");
            }
            if (!containsItemId(loadedItems, itemCount[0], record.getItemId())) {
                throw new DataFileException(DataFiles.WASTE, number,
                        "waste '" + record.getWasteId() + "' refers to item '"
                                + record.getItemId() + "', which is not in " + DataFiles.ITEMS);
            }
            checkWasteBatch(record, number, loadedBatches, batchCount[0]);
            loadedWaste[wasteCount[0]++] = record;
        });

        // ---- activity
        final ActivityLog[] loadedActivity = new ActivityLog[activity.entries().length];
        final int[] activityCount = {0};
        reader.forEachLine(DataFiles.ACTIVITY, (line, number) -> {
            ActivityLog entry = DataParser.parseActivity(line, number, DataFiles.ACTIVITY);
            if (activityCount[0] >= loadedActivity.length) {
                throw new DataFileException(DataFiles.ACTIVITY, number,
                        "holds more activity lines than the store allows ("
                                + loadedActivity.length + ")");
            }
            loadedActivity[activityCount[0]++] = entry;
        });

        // ---- settings, into a TEMPORARY object. The live one is untouched until every
        //      file above has parsed, so a bad settings line cannot leave a half-applied
        //      set of thresholds behind.
        final Settings parsed = new Settings();
        reader.forEachLine(DataFiles.SETTINGS,
                (line, number) -> SettingsParser.applyLine(line, number, DataFiles.SETTINGS, parsed));

        // Everything parsed. Only now is anything replaced.
        seasons.clear();
        for (int i = 0; i < seasonCount[0]; i++) {
            seasons.add(loadedSeasons[i]);
        }
        items.clear();
        for (int i = 0; i < itemCount[0]; i++) {
            items.add(loadedItems[i]);
        }
        batches.clear();
        for (int i = 0; i < batchCount[0]; i++) {
            batches.add(loadedBatches[i]);
        }
        sales.clear();
        for (int i = 0; i < saleCount[0]; i++) {
            sales.add(loadedSales[i]);
        }
        waste.clear();
        for (int i = 0; i < wasteCount[0]; i++) {
            waste.add(loadedWaste[i]);
        }
        activity.clear();
        for (int i = 0; i < activityCount[0]; i++) {
            activity.add(loadedActivity[i]);
        }
        settings.copySavedValuesFrom(parsed);
    }

    /**
     * Refuses to load a folder whose last save did not finish.
     *
     * <p>Three things in the folder can only be there because a save started and never
     * reached its end: the marker file this program writes for the duration of a save, a
     * backup file ({@code .bak}), and the staging name a backup is copied under
     * ({@code .bak.tmp}). Checking all three means a folder written by an older build, or
     * one left by a run that crashed between the marker and the cleanup, is caught too.</p>
     *
     * <p>A reversed save no longer leaves any of them behind, so this refusal is now about
     * the one case that really needs a person: a restore that failed, or a directory that
     * took a data file's name. Those are exactly the folders a normal restart must not
     * paper over.</p>
     *
     * <p>The alternative - loading whatever is there - is the failure this whole class is
     * built to prevent. The files would be a mixture of two moments: for example a new
     * batches.txt with an old sales.txt, which would leave stock that no sale explains.
     * The next save would then write that mixture back, and the truth would be gone. So
     * loading stops, and the message says what to look at instead of advising a plain
     * restart.</p>
     *
     * <p>This method only reads. Saying "nothing was changed" would be too strong - the
     * save that left these files may well have changed some of them - so the hint says
     * precisely that startup itself did not modify anything.</p>
     *
     * @throws DataFileException with {@link DataFileException#isDatasetConsistent()}
     *                           false and a recovery hint naming the files to compare
     */
    private void requireNoInterruptedSave() throws DataFileException {
        String marker = SAVE_IN_PROGRESS;
        String backups = namesWithSuffix(BACKUP_SUFFIX);
        String backupStaging = namesWithSuffix(BACKUP_STAGING_SUFFIX);

        String found = "";
        if (Files.exists(pathOf(marker))) {
            found = marker;
        }
        if (!backups.isEmpty()) {
            found = found.isEmpty() ? backups : found + ", " + backups;
        }
        if (!backupStaging.isEmpty()) {
            found = found.isEmpty() ? backupStaging : found + ", " + backupStaging;
        }
        if (found.isEmpty()) {
            return;
        }

        // The wording has to survive the case where there are no backups at all: a save
        // that stopped at a directory, for instance, has nothing to compare. So the hint
        // says what to look at rather than assuming the backups exist.
        String backupsPresent = namesWithSuffix(BACKUP_SUFFIX);
        String whatToCompare = backupsPresent.isEmpty()
                ? "If there are " + BACKUP_SUFFIX + " files in " + directory.toAbsolutePath()
                        + ", each one holds the contents its data file had before that save;"
                        + " compare them with the data files and copy the backup over any"
                        + " file you want back."
                : "The " + BACKUP_SUFFIX + " files in " + directory.toAbsolutePath()
                        + " (" + backupsPresent + ") hold the contents their data files had"
                        + " before that save. Compare them with the data files and copy the"
                        + " backup over any file you want back.";

        throw new DataFileException(found, 0,
                "was left behind by a save that did not finish, so the data files may hold"
                        + " a mixture of records from before and after that save. They are"
                        + " not loaded",
                null, false, false, false,
                // "Nothing was changed" was too strong: the save that left this folder may
                // well have replaced some of the files. Only this program's own restraint
                // is certain, so that is what the sentence claims.
                "Startup did not modify the files. " + whatToCompare
                        + " Then delete " + marker + " and every " + BACKUP_SUFFIX + " and "
                        + BACKUP_STAGING_SUFFIX + " file from the folder, and start again."
                        + " To start over from the sample data instead, rename the whole"
                        + " folder and run once more.");
    }

    /** "a.txt, b.txt" for the data files that currently have the given suffix. */
    private String namesWithSuffix(String suffix) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < DataFiles.ALL.length; i++) {
            String name = DataFiles.ALL[i] + suffix;
            if (Files.exists(pathOf(name))) {
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(name);
            }
        }
        return text.toString();
    }

    /**
     * Rejects a data folder where some files exist and others do not.
     *
     * <p>Seeding only happens when no file exists, so a folder with a gap is damaged: a
     * file was deleted, or a save was interrupted between two files. Loading the rest as
     * if the missing file were empty would drop records, and the next save would make
     * that permanent.</p>
     *
     * @throws DataFileException naming the first missing file
     */
    private void requireAllFilesPresent(DataFileReader reader) throws DataFileException {
        for (int i = 0; i < DataFiles.ALL.length; i++) {
            if (!reader.exists(DataFiles.ALL[i])) {
                throw new DataFileException(DataFiles.ALL[i], 0,
                        "is missing while the other data files exist. Restore it from a backup, "
                                + "or delete every file in the " + DataFiles.DATA_DIR
                                + " folder to start again from the sample data.");
            }
        }
    }

    /**
     * Checks that a sale's {@code batchDraws} text names real batches of the same item and
     * adds up to the quantity sold.
     *
     * <p>Without this a sale could claim to have come from another item's batch, or take
     * more than it records, and the stock figures would drift with nothing to show for it.
     * A sale recorded with no draws at all is accepted: that is what an imported historical
     * sale looks like, and the quantity is still counted by the velocity rule.</p>
     */
    private static void checkSaleDraws(SaleRecord sale, int lineNumber, Batch[] batches,
                                       int batchCount) throws DataFileException {
        String[] entries = sale.drawEntries();
        if (entries.length == 0) {
            return;   // no allocation recorded; nothing to reconcile
        }
        double recorded = 0;
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i].trim();
            String batchId = SaleRecord.drawBatchId(entry);
            double qty = SaleRecord.drawQuantity(entry);
            if (batchId.isEmpty()) {
                throw new DataFileException(DataFiles.SALES, lineNumber,
                        "sale '" + sale.getSaleId() + "' has an empty batch id in its draws ('"
                                + sale.getBatchDraws() + "')");
            }
            if (!(qty > 0)) {
                throw new DataFileException(DataFiles.SALES, lineNumber,
                        "sale '" + sale.getSaleId() + "' records draw '" + entry
                                + "' without a positive quantity");
            }
            Batch batch = findBatch(batches, batchCount, batchId);
            if (batch == null) {
                throw new DataFileException(DataFiles.SALES, lineNumber,
                        "sale '" + sale.getSaleId() + "' draws from batch '" + batchId
                                + "', which is not in " + DataFiles.BATCHES);
            }
            if (!batch.getItemId().equalsIgnoreCase(sale.getItemId())) {
                throw new DataFileException(DataFiles.SALES, lineNumber,
                        "sale '" + sale.getSaleId() + "' is for item '" + sale.getItemId()
                                + "' but draws from batch '" + batchId + "', which belongs to '"
                                + batch.getItemId() + "'");
            }
            recorded += qty;
        }
        if (Math.abs(recorded - sale.getQuantity()) > 0.0001) {
            throw new DataFileException(DataFiles.SALES, lineNumber,
                    "sale '" + sale.getSaleId() + "' records draws totalling "
                            + trim(recorded) + " but the sale quantity is "
                            + trim(sale.getQuantity()));
        }
    }

    /**
     * Checks that a waste record's batch exists and belongs to the same item.
     *
     * <p>Waste is attributed to a batch so the money lost can be traced to a delivery; a
     * record pointing at another item's batch would make that figure meaningless.</p>
     */
    private static void checkWasteBatch(WasteRecord record, int lineNumber, Batch[] batches,
                                        int batchCount) throws DataFileException {
        Batch batch = findBatch(batches, batchCount, record.getBatchId());
        if (batch == null) {
            throw new DataFileException(DataFiles.WASTE, lineNumber,
                    "waste '" + record.getWasteId() + "' refers to batch '"
                            + record.getBatchId() + "', which is not in " + DataFiles.BATCHES);
        }
        if (!batch.getItemId().equalsIgnoreCase(record.getItemId())) {
            throw new DataFileException(DataFiles.WASTE, lineNumber,
                    "waste '" + record.getWasteId() + "' is recorded against item '"
                            + record.getItemId() + "' but its batch '" + record.getBatchId()
                            + "' belongs to '" + batch.getItemId() + "'");
        }
    }

    private static Batch findBatch(Batch[] batches, int count, String batchId) {
        for (int i = 0; i < count; i++) {
            if (batches[i].getBatchId().equalsIgnoreCase(batchId.trim())) {
                return batches[i];
            }
        }
        return null;
    }

    private static String trim(double value) {
        long whole = (long) value;
        return (whole == value) ? String.valueOf(whole) : String.valueOf(value);
    }

    private static boolean containsItemId(StockItem[] items, int count, String itemId) {
        for (int i = 0; i < count; i++) {
            if (items[i].getItemId().equalsIgnoreCase(itemId)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------- save

    /** Suffix for a file being prepared; it is not read by the loader. */
    public static final String STAGING_SUFFIX = ".tmp";

    /** Suffix for a copy of a file as it was before the save that is running. */
    public static final String BACKUP_SUFFIX = ".bak";

    /**
     * Suffix for a backup that is still being copied.
     *
     * <p>A backup becomes trustworthy only when the copy that produced it finished, and a
     * half-written file at the real {@code .bak} name would be indistinguishable from a
     * good one. So the copy is written here first and renamed into place afterwards, the
     * same way the data files themselves are.</p>
     */
    public static final String BACKUP_STAGING_SUFFIX = BACKUP_SUFFIX + STAGING_SUFFIX;

    /**
     * Name of the file that exists only while a save is under way.
     *
     * <p>It cannot collide with a data file: the loader only ever reads the seven names in
     * {@link DataFiles#ALL}, and its suffix is not one the saver produces.</p>
     */
    public static final String SAVE_IN_PROGRESS = "save-in-progress" + STAGING_SUFFIX;

    /** How many files one save writes. */
    private static final int SAVE_PARTS = DataFiles.ALL.length;

    /**
     * Writes every store to its text file as one reversible step.
     *
     * <p>Writing seven files cannot be a single atomic operation with the tools allowed
     * here, so the save is arranged to make a failure recoverable instead:</p>
     *
     * <ol>
     *   <li><strong>Mark.</strong> A marker file is created. It stays there for as long as
     *       the save is unfinished, so a start-up that finds it knows the folder must not be
     *       loaded (see {@link #requireNoInterruptedSave}).</li>
     *   <li><strong>Stage.</strong> All seven files are fully written as
     *       {@code name.txt.tmp}. Nothing real has changed yet, so a failure here - a full
     *       disk is usually discovered at this point - costs nothing.</li>
     *   <li><strong>Swap.</strong> For each file the old version is copied to
     *       {@code name.txt.bak.tmp} and only renamed to {@code name.txt.bak} once the copy
     *       has finished, so a half-copied backup is never mistaken for a usable one. Only
     *       then is the staged file moved over the real name.</li>
     *   <li><strong>Undo or admit.</strong> If a swap fails, every file already swapped is
     *       copied back from its backup file, which needs no extra memory and works even
     *       when the disk is full - the backup already exists, it is only copied back.
     *       If that reverse copy also fails, the exception says the dataset is
     *       <em>not</em> consistent rather than pretending otherwise, and names the
     *       backup files so a person can put things right by hand.</li>
     *   <li><strong>Unmark.</strong> The marker is deleted once the save is whole, and the
     *       backups with it. It is <em>also</em> deleted when a save failed but every file
     *       it replaced was put back, after the backups have been cleaned up: the folder
     *       then holds exactly what the previous save left, so refusing to load it would
     *       turn a clean failure into a folder someone has to repair by hand. The marker
     *       survives only when the folder really does need looking at - a failed restore,
     *       or a directory in the way - which is the case startup must keep refusing.</li>
     * </ol>
     *
     * <p>A directory found where a data file belongs is left exactly as it is. It was put
     * there from outside, it may hold someone else's work, and deleting it to make room
     * would be the program destroying something it never created.</p>
     *
     * <p><strong>Where this returns instead of throwing.</strong> The steps above can fail
     * after the point of no return - once all seven files have been replaced - and that
     * failure is not the same as a save that did not happen. A marker that cannot be
     * deleted, or a backup that cannot be deleted, leaves the <em>new</em> records on disk.
     * Throwing a plain failure there would make the caller put the old figures back in
     * memory while the disk holds the new ones: memory and files describing two different
     * moments, which is the divergence this class exists to prevent. So that case returns a
     * {@link CleanupIncomplete}: the save committed, and the caller must keep the change and
     * say what is left to tidy.</p>
     *
     * @return the cleanup step that still has to be done, or {@code null} when the save
     *         finished completely
     * @throws DataFileException when no data file holds the new records - that is, the save
     *                           did not happen, or every file it replaced was put back.
     *                           Check {@link DataFileException#isDatasetConsistent()},
     *                           {@link DataFileException#isRecoveredOnStartup()} and
     *                           {@link DataFileException#getRecoveryHint()} to learn what
     *                           the folder now holds
     */
    public CleanupIncomplete saveAll(ItemStore items, BatchStore batches, SaleStore sales,
                                     WasteStore waste, SeasonStore seasons,
                                     ActivityLogStore activity)
            throws DataFileException {
        try {
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory);
            }
        } catch (IOException e) {
            throw new DataFileException(DataFiles.DATA_DIR, 0,
                    "could not create the data folder (" + e.getMessage() + ")", e);
        }

        // Every record is turned into text before the disk is touched, so the stores are
        // read once, at a single moment, and the file contents cannot disagree with
        // each other. Counting the save as "seven parts" keeps the array handling below
        // in the same plain-array style as the rest of the program.
        String[] names = new String[SAVE_PARTS];
        String[] contents = new String[SAVE_PARTS];
        for (int i = 0; i < SAVE_PARTS; i++) {
            names[i] = DataFiles.ALL[i];
        }
        contents[0] = seasonText(seasons);
        contents[1] = itemText(items);
        contents[2] = batchText(batches);
        contents[3] = saleText(sales);
        contents[4] = wasteText(waste);
        contents[5] = settings.toDataText();
        contents[6] = activityText(activity);

        markSaveInProgress();
        stageAll(names, contents);
        return swapAll(names);
    }

    private static String seasonText(SeasonStore seasons) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < seasons.count(); i++) {
            if (seasons.seasons()[i] != null) {
                text.append(seasons.seasons()[i].toDataLine()).append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    private static String itemText(ItemStore items) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.count(); i++) {
            if (items.items()[i] != null) {
                text.append(items.items()[i].toDataLine()).append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    private static String batchText(BatchStore batches) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < batches.count(); i++) {
            if (batches.batches()[i] != null) {
                text.append(batches.batches()[i].toDataLine()).append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    private static String saleText(SaleStore sales) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < sales.count(); i++) {
            if (sales.sales()[i] != null) {
                text.append(sales.sales()[i].toDataLine()).append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    private static String wasteText(WasteStore waste) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < waste.count(); i++) {
            if (waste.waste()[i] != null) {
                text.append(waste.waste()[i].toDataLine()).append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    private static String activityText(ActivityLogStore activity) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < activity.count(); i++) {
            if (activity.entries()[i] != null) {
                text.append(activity.entries()[i].toDataLine()).append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    /**
     * Writes every file under its staging name. On failure the staged files are removed
     * again, so a failed save leaves no .tmp files behind to confuse the next start.
     *
     * <p>Staging only ever writes files ending in {@code .tmp}; not one data file has been
     * replaced yet, so the folder is exactly as it was and nothing has to be undone. The
     * marker is therefore cleared here as well, otherwise a save that ran out of disk
     * before touching anything would leave a folder the next start refuses to load -
     * making the normal restart path useless for the simplest failure there is.</p>
     *
     * <p>If that clearing fails too, the data files are still untouched, so the change
     * certainly did not reach the disk; what is left is a marker that stops a start. The
     * report says exactly that, and the hint names the one file to delete.</p>
     */
    private void stageAll(String[] names, String[] contents) throws DataFileException {
        for (int i = 0; i < names.length; i++) {
            try {
                if (faultFor(names[i]).failsStaging()) {
                    // Injected at the first file, before anything is written.
                    throw new IOException("injected failure writing " + names[i]);
                }
                writeText(stagingPathOf(names[i]), contents[i]);
            } catch (IOException e) {
                discardStaged(names);
                // The staging copies are gone, so the marker has nothing left to describe.
                // If it cannot be deleted, nothing but the marker is left, which is said
                // rather than passed off as a clean abandonment.
                DataFileException stillMarked = clearSaveMarker(names, null);
                if (stillMarked != null) {
                    throw new DataFileException(names[i], 0,
                            "could not be written (" + e.getMessage() + "), and the save was"
                                    + " abandoned before any data file was replaced; the"
                                    + " marker that was created for it could not be removed"
                                    + " either",
                            e, false, true, false,
                            "No data file was changed: the new records were never written"
                                    + " to one. The folder is refused by a start-up only"
                                    + " because of " + stillMarked.getFileName()
                                    + ", which is left in " + directory.toAbsolutePath()
                                    + ". Delete it by hand, and start again as usual; the"
                                    + " data files load unchanged.");
                }
                throw new DataFileException(names[i], 0,
                        "could not be written (" + e.getMessage() + "), and the save was"
                                + " abandoned before any data file was replaced", e);
            }
        }
    }

    /**
     * Moves each staged file onto its real name, keeping the previous copy as a backup so
     * a later failure can be reversed.
     *
     * <p>The backup is copied to {@code name.txt.bak.tmp} and renamed to
     * {@code name.txt.bak} only once the copy has finished. A copy that fails half way
     * therefore leaves nothing at the name {@link #undoSwap} looks for, so it is never
     * read back as if it held the old contents - and any {@code .bak} left over from an
     * earlier save is deleted before the new one is published, so an old file can never be
     * taken for this save's backup.</p>
     *
     * @return the cleanup that could not be finished, or {@code null} when the save is
     *         complete in every respect
     * @throws DataFileException describing either a clean reversal or a dataset left
     *                           partly written; it is never thrown once all seven files
     *                           hold the new records
     */
    private CleanupIncomplete swapAll(String[] names) throws DataFileException {
        int swapped = 0;
        // What this attempt replaced, rather than what happened to be called .bak on disk.
        boolean[] copied = new boolean[names.length];
        // Files that cannot be put back at all, whatever this save does with them. There is
        // exactly one such case - a directory where the data file belongs - and it has to be
        // recorded before the backup is made, because the backup is made even then (the old
        // contents are copied aside from the directory), and a restore would otherwise see
        // "copied, restored" and call the folder whole when it is a file short.
        boolean[] blocked = new boolean[names.length];

        for (int i = 0; i < names.length; i++) {
            Path target = pathOf(names[i]);
            Path backup = backupPathOf(names[i]);
            try {
                if (Files.isDirectory(target)) {
                    // Something outside the program took the name. Files.copy would copy
                    // *into* the directory and report success, so the swap has to stop
                    // here instead - and the directory must not be emptied to make room.
                    blocked[i] = true;
                    throw new IOException("a directory is in the way of " + names[i]
                            + ", and it was left untouched");
                }
                if (Files.exists(target)) {
                    discardEntry(backupPathOf(names[i]), names[i] + BACKUP_SUFFIX);
                    Files.copy(target, backupStagingPathOf(names[i]),
                            StandardCopyOption.REPLACE_EXISTING);
                    if (faultFor(names[i]).failsBackup()) {
                        // Injected. The cut-short copy stays under the staging name, where
                        // no restore will ever pick it up.
                        throw new IOException("injected failure copying " + names[i] + " aside");
                    }
                    moveIntoPlace(backupStagingPathOf(names[i]), backup);
                    copied[i] = true;
                }
                if (faultFor(names[i]).failsSave()) {
                    // Injected: the backup is already in place, so the undo path runs
                    // exactly as it would for a real failure at this point. The staged
                    // file stays where it is, which is what a real failed move leaves too.
                    throw new IOException("injected failure replacing " + names[i]);
                }
                moveIntoPlace(stagingPathOf(names[i]), target);
                swapped++;
            } catch (IOException e) {
                // The file whose swap failed is included, so a move that half-happened is
                // dealt with too. undoSwap skips any file this attempt did not copy aside,
                // so a failed move - which touched nothing - is skipped rather than
                // "restored" over itself.
                throw undoSwap(names, i + 1, copied, blocked, e);
            }
        }

        // Past this point every data file holds the new records. Whatever goes wrong from
        // here is tidying up, and it must not be reported as a save that did not happen:
        // the backups are all that is now known to hold the old records, and undoing the
        // swap would mean copying them back over files that are already correct.
        if (SAVE_PARTS != swapped) {
            // A loop over seven names that ends without all seven swapped would mean the
            // loop was changed without this check; better to say so than to call a partial
            // swap a commit.
            throw new DataFileException(names[0], 0,
                    "only " + swapped + " of " + SAVE_PARTS + " files were replaced, which"
                            + " should not be possible; the folder needs looking at",
                    null, false, false, false, "");
        }
        return tidyUpAfterCommit(names);
    }

    /**
     * Removes the backups and the marker once all seven files hold the new records.
     *
     * <p>The order is deliberate. Backups first, because a folder with a backup and no
     * marker is still recognisable at start-up, while the reverse is a folder that looks
     * finished and is not. The marker goes last, after everything that made it necessary.</p>
     *
     * <p>The deletes are not quiet here. Once the new records are on disk, a leftover
     * {@code .bak} or a leftover marker is not litter: start-up refuses the folder on
     * either. A save that cannot remove them has committed, but it has not finished, and
     * saying so is the difference between a user who deletes one file and a user who
     * starts the program again and is turned away with no idea why.</p>
     *
     * @return {@code null} when everything was removed, otherwise what is still there
     */
    private CleanupIncomplete tidyUpAfterCommit(String[] names) {
        String backupStuck = deleteBackups(names);
        if (!backupStuck.isEmpty()) {
            // The marker stays as well, on purpose: two signs that this folder is not to be
            // loaded are better than one, and the marker also stops the next save from
            // overwriting the backup that is still needed by hand.
            return incomplete(names, "the copy of the old contents could not be deleted"
                    + " after the save finished", backupStuck);
        }
        String beforeStuck = deleteEach(backupStagingFilesOf(names));
        if (!beforeStuck.isEmpty()) {
            return incomplete(names, "a half-copied backup could not be deleted after the"
                    + " save finished", beforeStuck);
        }
        String markerStuck = deleteMarker();
        if (!markerStuck.isEmpty()) {
            return incomplete(names, "the marker that says a save is running could not be"
                    + " deleted after the save finished", markerStuck);
        }
        return null;
    }

    /** Deletes the seven backups, reporting each one a refused delete left behind. */
    private String deleteBackups(String[] names) {
        Path[] backups = backupsOf(names);
        StringBuilder stuck = new StringBuilder();
        for (int i = 0; i < backups.length; i++) {
            String name = backups[i].getFileName().toString();
            if (faultFor(names[i]).failsBackupDelete()) {
                append(stuck, name);
                continue;
            }
            try {
                Files.deleteIfExists(backups[i]);
            } catch (IOException e) {
                append(stuck, name);
            }
        }
        return stuck.toString();
    }

    /**
     * Deletes the marker, and reports its name when it could not be deleted.
     *
     * <p>Its own method because the marker is not one of the seven data files: the fault
     * that stands in for a refused delete is looked up by file name, and pairing the marker
     * with a data file's name would check a fault nobody set. The lookup here is the
     * marker's own name.</p>
     */
    private String deleteMarker() {
        String name = saveMarkerPath().getFileName().toString();
        if (faultFor(name).failsMarkerDelete()) {
            return name;
        }
        try {
            Files.deleteIfExists(saveMarkerPath());
            return "";
        } catch (IOException e) {
            return name;
        }
    }

    /**
     * Builds the report for a committed save whose cleanup stopped part way.
     *
     * <p>The exception it returns is a description, not a verdict of failure: memory must
     * keep the new records, because they are the ones on disk. It says that plainly, and
     * the hint names only the files that are really there, so nobody goes looking for a
     * backup that was already deleted.</p>
     */
    private CleanupIncomplete incomplete(String[] names, String what, String stuck) {
        String leftoverList = leftoverNames(names);
        return new CleanupIncomplete(new DataFileException(stuck, 0,
                what + " (" + stuck + ")", null, true, true, false,
                "The change was saved: all seven data files hold the new records, so the"
                        + " figures in memory are the saved ones. Nothing needs repairing in"
                        + " the records themselves. However, " + leftoverList + " is still"
                        + " in " + directory.toAbsolutePath() + ", and a start-up treats"
                        + " that as the sign of an unfinished save, so it would refuse this"
                        + " folder even though the records in it are whole. Delete"
                        + " " + leftoverList + " and start again: the new records then load"
                        + " as usual. Until then the folder is not loaded and further"
                        + " changes are refused."), leftoverList);
    }

    /**
     * Deletes each path, and reports the names it could not delete.
     *
     * <p>Unlike {@link #removeQuietly} this returns the failures, because after a commit a
     * leftover is not harmless: it is exactly what a start-up refuses the folder on.</p>
     */
    private static String deleteEach(Path[] paths) {
        StringBuilder stuck = new StringBuilder();
        for (int i = 0; i < paths.length; i++) {
            String name = paths[i].getFileName().toString();
            try {
                Files.deleteIfExists(paths[i]);
            } catch (IOException e) {
                append(stuck, name);
            }
        }
        return stuck.toString();
    }

    /**
     * Puts back every file this save replaced, then reports what the caller is left with.
     *
     * <p>Restoring copies the backup file over the target rather than moving it, so the
     * backup survives a failed restore and can be used by hand afterwards.</p>
     *
     * <p>The marker is decided here, because this is the only place that knows whether the
     * reversal actually completed:</p>
     * <ul>
     *   <li><strong>Everything put back.</strong> The folder is byte for byte what the
     *       previous save left, so the cleanup runs - staged files, backup staging files,
     *       every {@code .bak} - and the marker goes last. A new start then loads the
     *       folder normally, which is what the 503 promises.</li>
     *   <li><strong>Something not put back.</strong> The files may mix old and new records.
     *       Staged files and half-copied backups are still cleared, because neither can
     *       ever be used, but the backups and the marker stay: the backups are the only
     *       copy of the earlier contents, and the marker is what makes the next start
     *       refuse the folder instead of loading the mixture.</li>
     * </ul>
     *
     * @param attempted how many files the save got as far as, counting the one that failed
     * @param copied    which of those this save really did copy aside, so only a backup
     *                  that is known to be complete is ever restored from
     * @param blocked   which of those cannot be put back at all, because a directory holds
     *                  the name - reported as left alone rather than silently counted as
     *                  reversed
     */
    private DataFileException undoSwap(String[] names, int attempted, boolean[] copied,
                                       boolean[] blocked, IOException cause) {
        StringBuilder failed = new StringBuilder();
        StringBuilder leftAlone = new StringBuilder();
        for (int i = 0; i < attempted; i++) {
            if (blocked[i]) {
                // No restore can succeed here, whatever is or is not in the backup: the
                // name belongs to a directory that must not be emptied. Until this was
                // recorded separately, the loop skipped the file because no backup had
                // been made, and the folder was declared whole while it was one file short.
                append(leftAlone, names[i]);
                continue;
            }
            if (!copied[i]) {
                // No complete copy was made, so this file was never replaced and there is
                // nothing to put back. Either its swap was the one that failed, or it came
                // after the failure; a move either happens or it does not.
                continue;
            }
            Path target = pathOf(names[i]);
            Path backup = backupPathOf(names[i]);
            if (faultFor(names[i]).failsRestore()) {
                // Injected: this file is left as the failed save wrote it.
                append(failed, names[i]);
                continue;
            }
            if (Files.isDirectory(target)) {
                // Belt and braces: a directory that appeared between the swap and the
                // restore. A directory there was not created by this program and may hold
                // a person's files, so it is reported instead of being emptied.
                append(leftAlone, names[i]);
                continue;
            }
            try {
                // A move either happened or it did not, so whatever holds the name here is
                // either the file this save wrote or a leftover of the same name; both are
                // replaced by the copy from before the save.
                moveIntoPlace(backup, target);
            } catch (IOException e) {
                append(failed, names[i]);
            }
        }

        // Copies prepared for a swap that will never happen are of no use, whether or not
        // the reversal worked - the backups are what a restore reads, not these.
        removeQuietly(stagingFilesOf(names));
        removeQuietly(backupStagingFilesOf(names));

        if (failed.length() == 0 && leftAlone.length() == 0) {
            // Nothing on disk differs from before the save, so the backups are litter now.
            // They go first and the marker last, so a moment of power loss in between
            // leaves a folder with backups and no marker - which the next start can still
            // recognise, because it treats a leftover .bak as a sign too.
            removeQuietly(backupsOf(names));
            return clearSaveMarker(names, new DataFileException(names[attempted - 1], 0,
                    "could not be written (" + cause.getMessage() + "); the "
                            + attempted + " file(s) reached before the failure were put"
                            + " back, so the data files are exactly as they were before"
                            + " the save",
                    cause, false, true, true, ""));
        }

        // Leave the backups and the marker alone - they are the only copy of the previous
        // contents and the only sign that this folder must not be loaded as it is - and
        // say plainly what could not be done.
        String stuck = failed.length() == 0 ? leftAlone.toString() : failed.toString();
        // The directory obstacle already explains itself inside cause.getMessage(); adding
        // it again produced a sentence that said the same thing twice.
        String detail = cause.getMessage();
        // Only name backup files that exist. A directory in a file's place leaves no
        // backup for that file, so pointing at "waste.txt.bak" would send the person
        // hunting for something that was never written.
        String keptBackups = namesWithSuffix(BACKUP_SUFFIX);
        String compare = keptBackups.isEmpty()
                ? " No " + BACKUP_SUFFIX + " file was made, so there is nothing to copy"
                        + " back: the file named above (" + stuck + ") has to be put right"
                        + " by hand, from your own copy of the data."
                : " These " + BACKUP_SUFFIX + " files in " + directory
                        + " hold their data file's earlier contents: " + keptBackups
                        + ". Keep them, and compare " + stuck + " with the matching "
                        + BACKUP_SUFFIX + " files by hand before starting again.";
        return new DataFileException(stuck, 0,
                "could not be written (" + detail + "), and putting back the"
                        + " file(s) already replaced also failed, so the data files are"
                        + " now a mixture of old and new records",
                cause, false, false, false,
                "The saved data may be partly written and needs recovery." + compare
                        + " Keep the " + SAVE_IN_PROGRESS + " marker too: it is what makes"
                        + " the next start refuse this folder instead of loading the"
                        + " mixture.");
    }

    private static void append(StringBuilder text, String value) {
        if (text.length() > 0) {
            text.append(", ");
        }
        text.append(value);
    }

    /** Writes text to an exact path, replacing whatever is there. */
    private static void writeText(Path target, String content) throws IOException {
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write(content);
            writer.flush();
        }
    }

    /** Moves one file into place, falling back to a plain replace where needed. */
    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // Some file systems cannot move atomically; a plain replace is the fallback.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path stagingPathOf(String fileName) {
        return pathOf(fileName + STAGING_SUFFIX);
    }

    private Path backupPathOf(String fileName) {
        return pathOf(fileName + BACKUP_SUFFIX);
    }

    private Path backupStagingPathOf(String fileName) {
        return pathOf(fileName + BACKUP_STAGING_SUFFIX);
    }

    /** The marker that says a save started and has not finished. */
    public Path saveMarkerPath() {
        return pathOf(SAVE_IN_PROGRESS);
    }

    /**
     * Creates the marker for a save that is starting.
     *
     * <p>If it cannot be created the save stops here, before any data file is touched: a
     * half-written folder that nobody can recognise at start-up is worse than a save that
     * did not begin.</p>
     */
    private void markSaveInProgress() throws DataFileException {
        try {
            writeText(saveMarkerPath(), "");
        } catch (IOException e) {
            // The file name is already the exception's own prefix, and the deeper message
            // repeats the whole path, so the reason is kept without either of them.
            throw new DataFileException(SAVE_IN_PROGRESS, 0,
                    "could not be created, so the save was not started and no data file"
                            + " was changed (the folder refused a new file: "
                            + e.getMessage() + ")", e);
        }
    }

    /**
     * Ends the save by deleting the marker, once everything that made it necessary is gone.
     *
     * <p>Called last in every path where the folder is safe to load again: after a whole
     * save, after a staging failure that touched no data file, and after a rollback that
     * put every file back. Calling it last is the point - if the deletes before it fail
     * quietly, the marker is still there to make the next start look before loading.</p>
     *
     * <p>A marker that cannot be deleted is not swallowed silently. The folder is
     * consistent, but the next start would refuse it, and refusing with an explanation is
     * only acceptable if the explanation is true - so the failure is reported as the
     * clean outcome it is, with the one extra step it needs. {@code leftovers} names what
     * this operation is responsible for clearing; the files {@code .bak} and
     * {@code .bak.tmp} are always named as well, because
     * {@link #requireNoInterruptedSave} refuses on those too.</p>
     *
     * <p>It returns rather than throws so {@link #undoSwap}, which builds its exception to
     * return, can call it on its one clean outcome. That outcome carries a reason of its
     * own (the save failed); this one would replace it.</p>
     *
     * @param otherwise what to report when the marker was deleted
     * @return {@code otherwise}, or an explanation of the marker instead
     */
    private DataFileException clearSaveMarker(String[] names, DataFileException otherwise) {
        if (faultFor(SAVE_IN_PROGRESS).failsMarkerDelete()) {
            return markerStuck(names, otherwise);
        }
        try {
            Files.deleteIfExists(saveMarkerPath());
            return otherwise;
        } catch (IOException e) {
            return markerStuck(names, otherwise);
        }
    }

    /** The report for a marker that could not be removed on a non-committing path. */
    private DataFileException markerStuck(String[] names, DataFileException cause) {
        // Reached only on the non-committing paths - a staging failure, or a reversal
        // that put every file back. A whole save goes through tidyUpAfterCommit, where
        // the same failure has to keep the new records in memory and is reported as
        // incomplete rather than as a save that did not happen.
        return new DataFileException(SAVE_IN_PROGRESS, 0,
                "could not be removed after the save finished; no data file holds new"
                        + " records, but a start-up reads the marker as an unfinished save",
                cause, false, true, false,
                "The data files themselves are whole. Delete " + SAVE_IN_PROGRESS
                        + leftoverNames(names) + " from "
                        + directory.toAbsolutePath() + " and start again; the next start"
                        + " refuses the folder until then, because those files would"
                        + " otherwise be indistinguishable from a save that never"
                        + " finished.");
    }

    /** {@link #clearSaveMarker(String[], DataFileException)} for a caller that throws. */
    private void clearSaveMarker(String[] names) throws DataFileException {
        throwIfFailed(clearSaveMarker(names, null));
    }

    /** Throws the exception it is given, if there is one. Keeps callers one line each. */
    private static void throwIfFailed(DataFileException problem) throws DataFileException {
        if (problem != null) {
            throw problem;
        }
    }

    /**
     * " and whatever is left of a.txt.bak, ..." - what else a start-up would refuse on.
     *
     * <p>Only names something when the folders holds it, so the instruction does not send
     * a person looking for files that are already gone.</p>
     */
    private String leftoverNames(String[] names) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            String[] possible = {names[i] + BACKUP_SUFFIX, names[i] + BACKUP_STAGING_SUFFIX};
            for (String candidate : possible) {
                if (Files.exists(pathOf(candidate))) {
                    text.append(text.length() == 0 ? " and " : ", ").append(candidate);
                }
            }
        }
        return text.toString();
    }

    private void discardStaged(String[] names) {
        removeQuietly(stagingFilesOf(names));
    }

    private Path[] stagingFilesOf(String[] names) {
        Path[] paths = new Path[names.length];
        for (int i = 0; i < names.length; i++) {
            paths[i] = stagingPathOf(names[i]);
        }
        return paths;
    }

    private Path[] backupStagingFilesOf(String[] names) {
        Path[] paths = new Path[names.length];
        for (int i = 0; i < names.length; i++) {
            paths[i] = backupStagingPathOf(names[i]);
        }
        return paths;
    }

    private Path[] backupsOf(String[] names) {
        Path[] paths = new Path[names.length];
        for (int i = 0; i < names.length; i++) {
            paths[i] = backupPathOf(names[i]);
        }
        return paths;
    }

    /**
     * Clears an old file left at a name this save is about to reuse.
     *
     * <p>Only a regular file is deleted. A directory there is left alone and the save
     * fails with a message naming it: deleting a directory tree to make room is how a
     * program destroys work it did not create, and one save failing is the cheaper
     * mistake.</p>
     */
    private static void discardEntry(Path stale, String label) throws IOException {
        if (!Files.exists(stale)) {
            return;
        }
        if (Files.isDirectory(stale)) {
            throw new IOException("a directory is in the way of " + label
                    + ", and it was left untouched");
        }
        Files.delete(stale);
    }

    /**
     * Deletes files that only exist to support a save. Failing to delete a leftover is
     * not worth failing the save over: the loader ignores both suffixes, and the copies
     * are overwritten by the next save.
     */
    private static void removeQuietly(Path[] paths) {
        for (int i = 0; i < paths.length; i++) {
            try {
                Files.deleteIfExists(paths[i]);
            } catch (IOException ignored) {
                // Nothing useful can be done; the file is ignored by the loader.
            }
        }
    }
}
