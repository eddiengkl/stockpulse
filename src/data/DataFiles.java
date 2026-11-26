package data;

/**
 * The names and shapes of the text files StockPulse persists to.
 *
 * <p>Every file is plain UTF-8 text with one record per line and fields separated by a
 * single {@code '|'}. Nothing else is used: no CSV quoting, no escape characters, no
 * JSON. That keeps the files readable in Notepad and keeps the reader small enough to
 * be explained in the report.</p>
 *
 * <p>The {@code '|'} choice is only safe because {@code DataText.safeField} rejects a
 * pipe and line breaks in any text field, so a stray pipe cannot shift a record's
 * fields. The format is documented next to the constant for each file so the writer and
 * the parser cannot drift apart.</p>
 */
public final class DataFiles {

    /** Directory that holds every saved text file. */
    public static final String DATA_DIR = "data";

    /** Item master list. See {@link #ITEMS_FIELDS}. */
    public static final String ITEMS = "items.txt";
    /** Delivery batches, which is where actual stock lives. */
    public static final String BATCHES = "batches.txt";
    /** Sales ledger, scanned for the 14-day velocity window. */
    public static final String SALES = "sales.txt";
    /** Waste ledger, used for the money-lost figure. */
    public static final String WASTE = "waste.txt";
    /** Season names and month ranges. */
    public static final String SEASONS = "seasons.txt";
    /** Key=value settings, one per line. */
    public static final String SETTINGS = "settings.txt";
    /** Audit trail: who changed what, when. */
    public static final String ACTIVITY = "activity.txt";

    /**
     * items.txt, 13 fields:
     * <pre>
     * itemId|name|category|type|unit|sellingPrice|unitCost|shelfLifeDays|packSize|supplier|leadTimeDays|season|seasonalMultiplier
     * </pre>
     * {@code type} is {@code PERISHABLE} or {@code LONG_LIFE}; {@code season} is
     * {@code -} when the item is not seasonal.
     */
    public static final int ITEMS_FIELDS = 13;

    /**
     * batches.txt, 7 fields:
     * <pre>
     * batchId|itemId|qtyReceived|qtyRemaining|receivedDate|expiryDate|unitCost
     * </pre>
     * {@code expiryDate} is {@code -} for stock that cannot expire.
     */
    public static final int BATCHES_FIELDS = 7;

    /**
     * sales.txt, 6 fields:
     * <pre>
     * saleId|itemId|date|quantity|unitPrice|batchDraws
     * </pre>
     * {@code batchDraws} may be empty (two pipes next to each other).
     */
    public static final int SALES_FIELDS = 6;

    /**
     * waste.txt, 7 fields:
     * <pre>
     * wasteId|batchId|itemId|date|quantity|unitCost|reason
     * </pre>
     */
    public static final int WASTE_FIELDS = 7;

    /**
     * seasons.txt, 3 fields:
     * <pre>
     * name|startMonth|endMonth
     * </pre>
     */
    public static final int SEASONS_FIELDS = 3;

    /**
     * activity.txt, 4 fields:
     * <pre>
     * dd/MM/yyyy HH:mm|role|action|detail
     * </pre>
     * The timestamp is the first field and the detail is last, so a detail that happens
     * to contain spaces needs no special handling.
     */
    public static final int ACTIVITY_FIELDS = 4;

    /** Prefix for a comment line; ignored by the reader, never written by the saver. */
    public static final String COMMENT_PREFIX = "#";

    /** All data file names, in the order the saver writes them. */
    public static final String[] ALL = {SEASONS, ITEMS, BATCHES, SALES, WASTE, SETTINGS, ACTIVITY};

    private DataFiles() {
    }

    /** True for a blank line or a comment, which every reader skips. */
    public static boolean isIgnorable(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX);
    }
}
