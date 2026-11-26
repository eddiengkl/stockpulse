package model;

import java.time.LocalDate;

/**
 * Abstract parent of the inventory hierarchy.
 *
 * <p>Every stock item in StockPulse is either a {@link PerishableItem} (it can go
 * off) or a {@link LongLifeItem} (it cannot). The two subclasses answer the same
 * questions differently, which is where polymorphism is demonstrated: the risk
 * engine calls {@link #daysUntilExpiry} on every item in one array without ever
 * asking which subclass it is holding.</p>
 *
 * <p>All documented field rules are enforced by the constructor and setters, so an
 * invalid item cannot exist. Violations are unchecked
 * {@link IllegalArgumentException}s - the service layer reports user mistakes with its
 * own custom exceptions, while these guard against programming mistakes.</p>
 */
public abstract class StockItem {

    /** Tag written to items.txt and read back when the file is loaded. */
    public static final String TYPE_PERISHABLE = "PERISHABLE";
    public static final String TYPE_LONGLIFE = "LONG_LIFE";

    /**
     * Returned by {@link #discountPercentFor(int)} when no discount rule is agreed.
     * It is a flag, not a percentage: the UI must show "not configured" rather than
     * "0.0%", which would read as a recommendation of no discount.
     */
    public static final double DISCOUNT_NOT_CONFIGURED = -1.0;

    private String itemId;          // unique, e.g. "I001"
    private String name;
    private String category;
    private String unit;            // "L", "bottle", "pcs" - display label only
    private double sellingPrice;    // RM per unit, >= 0
    private double unitCost;        // RM per unit, >= 0
    private int shelfLifeDays;      // days from delivery until it must be used, >= 0
    private int packSize;           // supplier pack size, reorder qty is rounded up to it, >= 1
    private String supplierName;
    private int leadTimeDays;       // days from ordering to receiving, >= 0
    private Season season;          // null when the item is not seasonal
    private double seasonalMultiplier; // 1.00 when not seasonal, otherwise > 0

    /**
     * @param itemId              unique id, used in data files and URLs
     * @param name                display name
     * @param category            free-text grouping shown in filters
     * @param unit                display unit: L, bottle, pcs
     * @param sellingPrice        RM per unit, must be &gt;= 0
     * @param unitCost            RM per unit, must be &gt;= 0
     * @param shelfLifeDays       days of usable life from delivery, must be &gt;= 0
     * @param packSize            supplier pack size, must be &gt;= 1
     * @param supplierName        free-text supplier name
     * @param leadTimeDays        delivery delay in days, must be &gt;= 0
     * @throws IllegalArgumentException when any of the above does not hold
     */
    protected StockItem(String itemId, String name, String category, String unit,
                        double sellingPrice, double unitCost, int shelfLifeDays,
                        int packSize, String supplierName, int leadTimeDays) {
        // Validate through the static checks, not the setters: a setter could be
        // overridden by a subclass and would then see a half-built object.
        this.itemId = DataText.safeField(itemId, "item id");
        this.name = DataText.safeField(name, "item name");
        this.category = DataText.safeField(category, "category");
        this.unit = DataText.safeField(unit, "unit");
        this.sellingPrice = DataText.nonNegative(sellingPrice, "selling price");
        this.unitCost = DataText.nonNegative(unitCost, "unit cost");
        this.shelfLifeDays = DataText.nonNegative(shelfLifeDays, "shelf life in days");
        this.packSize = DataText.atLeastOne(packSize, "Pack size");
        this.supplierName = DataText.safeField(supplierName, "supplier name");
        this.leadTimeDays = DataText.nonNegative(leadTimeDays, "lead time in days");
        this.season = null;
        this.seasonalMultiplier = 1.00;
    }

    // ---------------------------------------------------------------- type tag

    /** Short label used by the data file and by the UI badge. */
    public abstract String itemType();

    // ------------------------------------------------- methods subclasses change

    /**
     * Days from {@code today} until this stock must be used up.
     * Perishable items return the earliest batch expiry; long-life items return
     * {@link Integer#MAX_VALUE} because they cannot expire.
     *
     * @param batchExpiryDates expiry dates of this item's batches, as epoch days, unsorted
     * @param today            the effective business date
     */
    public abstract int daysUntilExpiry(long[] batchExpiryDates, LocalDate today);

    /**
     * Whether this kind of item can be flagged for expiry at all. The risk engine
     * uses this to skip long-life items instead of inventing expiry dates for them.
     */
    public abstract boolean canExpire();

    /**
     * Percentage off the selling price suggested for a batch that will not sell before
     * its date, or {@link #DISCOUNT_NOT_CONFIGURED} when no rule has been agreed.
     *
     * <p>Expired stock is not discounted - it is waste. This method is only asked about
     * batches with at least one day left.</p>
     *
     * @param daysLeft days remaining before expiry, must be &gt;= 1
     */
    public abstract double discountPercentFor(int daysLeft);

    /**
     * One-line stock description for the item list, e.g.
     * "18 L in 2 batches, oldest 3 days left" or "56 pcs in 1 batch, no expiry".
     *
     * @param totalQty   quantity on hand across batches
     * @param batchCount number of batches holding stock
     * @param daysLeft   earliest days-left value from {@link #daysUntilExpiry}
     */
    public abstract String describeStock(double totalQty, int batchCount, int daysLeft);

    /**
     * Text form of a discount percentage for the UI.
     *
     * @return "not configured" when the value is {@link #DISCOUNT_NOT_CONFIGURED},
     *         "0%" for a genuine zero, otherwise "20%"
     */
    public static String discountText(double percent) {
        if (percent == DISCOUNT_NOT_CONFIGURED) {
            return "not configured";
        }
        return NumberText.quantity(percent) + "%";
    }

    // ----------------------------------------------------------- shared helpers

    /** True when this item has a season and a multiplier that actually changes demand. */
    public boolean isSeasonal() {
        return season != null && seasonalMultiplier != 1.00;
    }

    /** Multiplier to apply to observed sales velocity; 1.00 when not seasonal. */
    public double seasonalMultiplierFor(Season activeSeason) {
        if (season == null || activeSeason == null) {
            return 1.00;
        }
        return season.getName().equalsIgnoreCase(activeSeason.getName()) ? seasonalMultiplier : 1.00;
    }

    /** Copy of the display fields, used by the API layer and by logs. */
    public String summary() {
        return itemId + " - " + name + " (" + unit + ")";
    }

    /**
     * A quantity with its unit, pluralised when the unit is a countable word.
     *
     * <p>"5 bottles" but "24 L": the litre symbol is never pluralised, and neither are
     * the other short measurement symbols a café would use. Writing "5 bottle in 1
     * batch" was the bug this fixes. The test is the length and the shape of the unit
     * rather than a list of exceptions, so "bag", "bottle", "pcs" and "box" all work,
     * while "L", "kg", "g" and "ml" are left alone.</p>
     *
     * @param quantity amount on hand or sold
     * @return e.g. "1 bottle", "5 bottles", "24 L"
     */
    public String describeQuantity(double quantity) {
        return NumberText.quantity(quantity) + " " + pluralUnit(quantity);
    }

    /**
     * The unit word for a quantity: unchanged for one, pluralised for more.
     *
     * <p>Three cases, in this order:</p>
     * <ol>
     *   <li>a quantity of exactly one keeps the unit as written - "1 bottle";</li>
     *   <li>a measurement symbol (two characters or fewer with no vowel, such as "L",
     *       "kg", "g", "ml") is never changed - "24 L";</li>
     *   <li>anything else is a word, so it gains an "s" - "5 bottles". A unit already
     *       written in the plural ("pcs", "boxes", "bags") is left alone rather than
     *       becoming "pcss".</li>
     * </ol>
     *
     * <p>Keeping this in one place means every message the model builds agrees, instead
     * of each one repeating its own ternary.</p>
     */
    public String pluralUnit(double quantity) {
        if (Math.abs(quantity - 1.0) < 0.0000001) {
            return unit;
        }
        if (isSymbol(unit) || looksPlural(unit)) {
            return unit;
        }
        return unit + "s";
    }

    /** True when the unit looks like a measurement symbol rather than a word. */
    private static boolean isSymbol(String unit) {
        if (unit.length() > 2) {
            return false;
        }
        String lower = unit.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the unit is already written in the plural, so adding another "s" would
     * be wrong. Covers "pcs" and words ending in "s" (bags, boxes, cartons).
     */
    private static boolean looksPlural(String unit) {
        return unit.length() > 1 && unit.toLowerCase().endsWith("s");
    }

    /** One pipe-delimited line in the documented items.txt field order. */
    public String toDataLine() {
        return itemId + "|" + name + "|" + category + "|" + itemType() + "|" + unit + "|"
                + NumberText.money(sellingPrice) + "|" + NumberText.money(unitCost) + "|"
                + shelfLifeDays + "|" + packSize + "|" + supplierName + "|" + leadTimeDays + "|"
                + (season == null ? "-" : season.getName()) + "|"
                + NumberText.money(seasonalMultiplier);
    }

    // ----------------------------------------------------------------- getters

    public String getItemId() { return itemId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getUnit() { return unit; }
    public double getSellingPrice() { return sellingPrice; }
    public double getUnitCost() { return unitCost; }
    public int getShelfLifeDays() { return shelfLifeDays; }
    public int getPackSize() { return packSize; }
    public String getSupplierName() { return supplierName; }
    public int getLeadTimeDays() { return leadTimeDays; }
    public Season getSeason() { return season; }
    public double getSeasonalMultiplier() { return seasonalMultiplier; }

    /**
     * An independent copy of this item, including its season and multiplier.
     *
     * <p>Used by the snapshot taken before a change is saved, so a failed write can put
     * memory back exactly as the files still have it.</p>
     *
     * <p>The season is copied rather than shared. A season's month range is editable in
     * place, so a copy that kept the same {@link Season} reference would follow any
     * change made to it - and the snapshot would stop being a record of the saved state.
     * {@link data.Snapshot} then re-points each restored item at the restored season
     * object, so the item and the store end up agreeing on the same instance.</p>
     *
     * <p>The {@code instanceof} here is a factory decision - which subclass to build -
     * not business logic. It is the same kind of choice the data loader makes when it
     * reads a type tag, and it is the only place that has to know the subclasses
     * exist.</p>
     */
    public StockItem copy() {
        StockItem copy = canExpire()
                ? new PerishableItem(itemId, name, category, unit, sellingPrice, unitCost,
                        shelfLifeDays, packSize, supplierName, leadTimeDays)
                : new LongLifeItem(itemId, name, category, unit, sellingPrice, unitCost,
                        shelfLifeDays, packSize, supplierName, leadTimeDays);
        copy.season = (season == null) ? null : season.copy();
        copy.seasonalMultiplier = this.seasonalMultiplier;
        return copy;
    }

    // ----------------------------------------------------------------- setters

    /** @throws IllegalArgumentException when the id is blank or contains '|' or a line break */
    public void setItemId(String itemId) {
        this.itemId = DataText.safeField(itemId, "item id");
    }

    /** @throws IllegalArgumentException when the name is blank or contains '|' or a line break */
    public void setName(String name) {
        this.name = DataText.safeField(name, "item name");
    }

    /** @throws IllegalArgumentException when the category is blank or contains '|' or a line break */
    public void setCategory(String category) {
        this.category = DataText.safeField(category, "category");
    }

    /** @throws IllegalArgumentException when the unit is blank or contains '|' or a line break */
    public void setUnit(String unit) {
        this.unit = DataText.safeField(unit, "unit");
    }

    /** @throws IllegalArgumentException when the price is negative or not a number */
    public void setSellingPrice(double sellingPrice) {
        this.sellingPrice = DataText.nonNegative(sellingPrice, "selling price");
    }

    /** @throws IllegalArgumentException when the cost is negative or not a number */
    public void setUnitCost(double unitCost) {
        this.unitCost = DataText.nonNegative(unitCost, "unit cost");
    }

    /** @throws IllegalArgumentException when the shelf life is negative */
    public void setShelfLifeDays(int shelfLifeDays) {
        this.shelfLifeDays = DataText.nonNegative(shelfLifeDays, "shelf life in days");
    }

    /** @throws IllegalArgumentException when the pack size is below 1 */
    public void setPackSize(int packSize) {
        this.packSize = DataText.atLeastOne(packSize, "Pack size");
    }

    /** @throws IllegalArgumentException when the supplier is blank or contains '|' or a line break */
    public void setSupplierName(String supplierName) {
        this.supplierName = DataText.safeField(supplierName, "supplier name");
    }

    /** @throws IllegalArgumentException when the lead time is negative */
    public void setLeadTimeDays(int leadTimeDays) {
        this.leadTimeDays = DataText.nonNegative(leadTimeDays, "lead time in days");
    }

    public void setSeason(Season season) {
        this.season = season;
    }

    /** A multiplier of 0 or less is meaningless, so it falls back to 1.00. */
    public void setSeasonalMultiplier(double seasonalMultiplier) {
        this.seasonalMultiplier = (seasonalMultiplier > 0) ? seasonalMultiplier : 1.00;
    }

    @Override
    public String toString() {
        return summary();
    }
}
