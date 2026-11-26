package data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import exception.DataFileException;
import model.ActivityLog;
import model.Batch;
import model.DateText;
import model.LongLifeItem;
import model.NumberText;
import model.PerishableItem;
import model.SaleRecord;
import model.Season;
import model.StockItem;
import model.WasteRecord;

/**
 * Turns one line of a data file into one model object, or throws
 * {@link DataFileException} naming the file and the line.
 *
 * <p>The model already validates every field, but it throws
 * {@link IllegalArgumentException} which says nothing about where the bad value came
 * from. Every construction here is wrapped so that exception is re-thrown as a
 * {@link DataFileException} carrying the file name and line number.</p>
 *
 * <p>Every method takes the raw line and its line number rather than a wrapper object,
 * so no per-line object is allocated and nothing is stored between lines.</p>
 *
 * <p>Nothing here writes to disk. There is deliberately no "skip this line" path: one
 * unreadable line stops the load.</p>
 */
public final class DataParser {

    /** Timestamp format used by activity.txt, matching {@link ActivityLog#toDataLine()}. */
    private static final DateTimeFormatter ACTIVITY_STAMP =
            DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm");

    private DataParser() {
    }

    // -------------------------------------------------------------------- items

    /**
     * Parses one items.txt line.
     *
     * <pre>
     * itemId|name|category|type|unit|sellingPrice|unitCost|shelfLifeDays|packSize|supplier|leadTimeDays|season|seasonalMultiplier
     * </pre>
     *
     * @throws DataFileException when a field is missing, the type tag is unknown, or the
     *                           model rejects a value
     */
    public static StockItem parseItem(String raw, int lineNumber, String fileName,
                                      Season[] seasons, int seasonCount) throws DataFileException {
        String[] f = DataFileReader.split(raw, lineNumber, fileName, DataFiles.ITEMS_FIELDS);

        String itemId = DataFileReader.requireField(f[0], lineNumber, fileName, "itemId");
        String name = DataFileReader.requireField(f[1], lineNumber, fileName, "name");
        String category = DataFileReader.requireField(f[2], lineNumber, fileName, "category");
        String type = DataFileReader.requireField(f[3], lineNumber, fileName, "type");
        String unit = DataFileReader.requireField(f[4], lineNumber, fileName, "unit");
        double sellingPrice = parseDouble(f[5], lineNumber, fileName, "sellingPrice");
        double unitCost = parseDouble(f[6], lineNumber, fileName, "unitCost");
        int shelfLifeDays = parseInt(f[7], lineNumber, fileName, "shelfLifeDays");
        int packSize = parseInt(f[8], lineNumber, fileName, "packSize");
        String supplier = DataFileReader.requireField(f[9], lineNumber, fileName, "supplier");
        int leadTimeDays = parseInt(f[10], lineNumber, fileName, "leadTimeDays");
        double multiplier = parseDouble(f[12], lineNumber, fileName, "seasonalMultiplier");

        StockItem item;
        try {
            if (StockItem.TYPE_PERISHABLE.equalsIgnoreCase(type)) {
                item = new PerishableItem(itemId, name, category, unit, sellingPrice, unitCost,
                        shelfLifeDays, packSize, supplier, leadTimeDays);
            } else if (StockItem.TYPE_LONGLIFE.equalsIgnoreCase(type)) {
                item = new LongLifeItem(itemId, name, category, unit, sellingPrice, unitCost,
                        shelfLifeDays, packSize, supplier, leadTimeDays);
            } else {
                throw new DataFileException(fileName, lineNumber,
                        "unknown item type '" + type + "'; expected "
                                + StockItem.TYPE_PERISHABLE + " or " + StockItem.TYPE_LONGLIFE);
            }
        } catch (IllegalArgumentException e) {
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }

        String seasonName = f[11];
        if (!seasonName.isEmpty() && !seasonName.equals("-")) {
            Season season = findSeason(seasons, seasonCount, seasonName);
            if (season == null) {
                // The item's file names a season that seasons.txt never defines. Loading it
                // anyway would silently drop the seasonal multiplier, so it is an error.
                throw new DataFileException(fileName, lineNumber,
                        "item '" + itemId + "' names season '" + seasonName
                                + "' but no such season is defined in " + DataFiles.SEASONS);
            }
            item.setSeason(season);
        }
        item.setSeasonalMultiplier(multiplier);
        return item;
    }

    // ------------------------------------------------------------------ batches

    /**
     * Parses one batches.txt line.
     *
     * <pre>
     * batchId|itemId|qtyReceived|qtyRemaining|receivedDate|expiryDate|unitCost
     * </pre>
     *
     * @throws DataFileException when a field is missing, a date is not dd/MM/yyyy, or the
     *                           model rejects a value
     */
    public static Batch parseBatch(String raw, int lineNumber, String fileName)
            throws DataFileException {
        String[] f = DataFileReader.split(raw, lineNumber, fileName, DataFiles.BATCHES_FIELDS);

        String batchId = DataFileReader.requireField(f[0], lineNumber, fileName, "batchId");
        String itemId = DataFileReader.requireField(f[1], lineNumber, fileName, "itemId");
        double qtyReceived = parseDouble(f[2], lineNumber, fileName, "qtyReceived");
        double qtyRemaining = parseDouble(f[3], lineNumber, fileName, "qtyRemaining");
        LocalDate received = parseDate(f[4], lineNumber, fileName, "receivedDate");
        LocalDate expiry = parseOptionalDate(f[5], lineNumber, fileName, "expiryDate");
        double unitCost = parseDouble(f[6], lineNumber, fileName, "unitCost");

        try {
            return new Batch(batchId, itemId, qtyReceived, qtyRemaining, received, expiry, unitCost);
        } catch (IllegalArgumentException e) {
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------- sales

    /**
     * Parses one sales.txt line.
     *
     * <pre>
     * saleId|itemId|date|quantity|unitPrice|batchDraws
     * </pre>
     *
     * @throws DataFileException when a field is missing or outside its allowed range
     */
    public static SaleRecord parseSale(String raw, int lineNumber, String fileName)
            throws DataFileException {
        String[] f = DataFileReader.split(raw, lineNumber, fileName, DataFiles.SALES_FIELDS);

        String saleId = DataFileReader.requireField(f[0], lineNumber, fileName, "saleId");
        String itemId = DataFileReader.requireField(f[1], lineNumber, fileName, "itemId");
        LocalDate date = parseDate(f[2], lineNumber, fileName, "date");
        double quantity = parseDouble(f[3], lineNumber, fileName, "quantity");
        double unitPrice = parseDouble(f[4], lineNumber, fileName, "unitPrice");
        String draws = f[5];   // may legitimately be empty

        try {
            return new SaleRecord(saleId, itemId, date, quantity, unitPrice, draws);
        } catch (IllegalArgumentException e) {
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------- waste

    /**
     * Parses one waste.txt line.
     *
     * <pre>
     * wasteId|batchId|itemId|date|quantity|unitCost|reason
     * </pre>
     */
    public static WasteRecord parseWaste(String raw, int lineNumber, String fileName)
            throws DataFileException {
        String[] f = DataFileReader.split(raw, lineNumber, fileName, DataFiles.WASTE_FIELDS);

        String wasteId = DataFileReader.requireField(f[0], lineNumber, fileName, "wasteId");
        String batchId = DataFileReader.requireField(f[1], lineNumber, fileName, "batchId");
        String itemId = DataFileReader.requireField(f[2], lineNumber, fileName, "itemId");
        LocalDate date = parseDate(f[3], lineNumber, fileName, "date");
        double quantity = parseDouble(f[4], lineNumber, fileName, "quantity");
        double unitCost = parseDouble(f[5], lineNumber, fileName, "unitCost");
        String reason = f[6];

        try {
            return new WasteRecord(wasteId, batchId, itemId, date, quantity, unitCost, reason);
        } catch (IllegalArgumentException e) {
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ seasons

    /**
     * Parses one seasons.txt line.
     *
     * <pre>
     * name|startMonth|endMonth
     * </pre>
     */
    public static Season parseSeason(String raw, int lineNumber, String fileName)
            throws DataFileException {
        String[] f = DataFileReader.split(raw, lineNumber, fileName, DataFiles.SEASONS_FIELDS);

        String name = DataFileReader.requireField(f[0], lineNumber, fileName, "name");
        int startMonth = parseInt(f[1], lineNumber, fileName, "startMonth");
        int endMonth = parseInt(f[2], lineNumber, fileName, "endMonth");

        try {
            return new Season(name, startMonth, endMonth);
        } catch (exception.InvalidSeasonException e) {
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- activity

    /**
     * Parses one activity.txt line.
     *
     * <pre>
     * dd/MM/yyyy HH:mm|role|action|detail
     * </pre>
     */
    public static ActivityLog parseActivity(String raw, int lineNumber, String fileName)
            throws DataFileException {
        String[] f = DataFileReader.split(raw, lineNumber, fileName, DataFiles.ACTIVITY_FIELDS);

        String stamp = DataFileReader.requireField(f[0], lineNumber, fileName, "timestamp");
        String role = DataFileReader.requireField(f[1], lineNumber, fileName, "role");
        String action = DataFileReader.requireField(f[2], lineNumber, fileName, "action");
        String detail = DataFileReader.requireField(f[3], lineNumber, fileName, "detail");

        LocalDateTime timestamp;
        try {
            timestamp = LocalDateTime.parse(stamp, ACTIVITY_STAMP);
        } catch (DateTimeParseException e) {
            throw new DataFileException(fileName, lineNumber,
                    "date/time '" + stamp + "' is not a valid dd/MM/yyyy HH:mm value", e);
        }
        try {
            return new ActivityLog(timestamp, role, action, detail);
        } catch (IllegalArgumentException e) {
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Finds a season by name, or null. Linear scan: there are only a handful. */
    public static Season findSeason(Season[] seasons, int count, String name) {
        for (int i = 0; i < count; i++) {
            if (seasons[i] != null && seasons[i].getName().equalsIgnoreCase(name.trim())) {
                return seasons[i];
            }
        }
        return null;
    }

    private static double parseDouble(String text, int lineNumber, String fileName,
                                      String fieldName) throws DataFileException {
        if (text.isEmpty()) {
            throw new DataFileException(fileName, lineNumber,
                    "field '" + fieldName + "' cannot be empty");
        }
        try {
            return NumberText.parseDouble(text, fieldName);
        } catch (IllegalArgumentException e) {
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }
    }

    private static int parseInt(String text, int lineNumber, String fileName, String fieldName)
            throws DataFileException {
        if (text.isEmpty()) {
            throw new DataFileException(fileName, lineNumber,
                    "field '" + fieldName + "' cannot be empty");
        }
        try {
            return NumberText.parseInt(text, fieldName);
        } catch (IllegalArgumentException e) {
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }
    }

    private static LocalDate parseDate(String text, int lineNumber, String fileName,
                                       String fieldName) throws DataFileException {
        if (text.isEmpty()) {
            throw new DataFileException(fileName, lineNumber,
                    "field '" + fieldName + "' cannot be empty");
        }
        try {
            return DateText.parse(text);
        } catch (IllegalArgumentException e) {
            throw new DataFileException(fileName, lineNumber,
                    e.getMessage() + " (in field '" + fieldName + "')", e);
        }
    }

    private static LocalDate parseOptionalDate(String text, int lineNumber, String fileName,
                                               String fieldName) throws DataFileException {
        if (text.isEmpty() || text.equals("-")) {
            return null;
        }
        return parseDate(text, lineNumber, fileName, fieldName);
    }
}
