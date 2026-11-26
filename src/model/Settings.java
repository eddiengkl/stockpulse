package model;

import java.time.LocalDate;
import java.time.Month;

/**
 * The café's editable thresholds and demo switches.
 *
 * <p>Every number here is a default, not a fact: the manager can change them in
 * Settings and they are saved to settings.txt.</p>
 */
public class Settings {

    /** Defaults agreed with the user; all four are editable at runtime. */
    public static final int DEFAULT_WINDOW_DAYS = 14;
    public static final int DEFAULT_SAFETY_MARGIN_DAYS = 1;
    public static final int DEFAULT_REORDER_HORIZON_DAYS = 7;
    public static final String DEFAULT_ACTIVE_SEASON = "Dry";

    private int salesWindowDays = DEFAULT_WINDOW_DAYS;
    private int safetyMarginDays = DEFAULT_SAFETY_MARGIN_DAYS;
    private int reorderHorizonDays = DEFAULT_REORDER_HORIZON_DAYS;
    private String activeSeasonName = DEFAULT_ACTIVE_SEASON;
    private boolean seasonFromDate = false;    // true = derive active season from the date
    private LocalDate demoDate = null;         // null = use the real system date

    public Settings() {
    }

    /**
     * The date all calculations use: the demo override when one is set, otherwise the
     * system date. The demo override is intentionally not saved to disk, so a forgotten
     * demo date cannot leak into the next normal session.
     */
    public LocalDate effectiveDate() {
        return (demoDate != null) ? demoDate : LocalDate.now();
    }

    public boolean isDemoDate() {
        return demoDate != null;
    }

    /**
     * Resolves which season is active.
     *
     * @param seasons   all known seasons, in array order
     * @param seasonCount how many of them are real
     * @return the matching season, or null when seasonFromDate is off and no season
     *         matches the name, or no season covers the current month
     */
    public Season resolveActiveSeason(Season[] seasons, int seasonCount) {
        if (seasonFromDate) {
            Month month = effectiveDate().getMonth();
            for (int i = 0; i < seasonCount; i++) {
                if (seasons[i] != null && seasons[i].covers(month.getValue())) {
                    return seasons[i];
                }
            }
            return null;
        }
        for (int i = 0; i < seasonCount; i++) {
            if (seasons[i] != null && seasons[i].getName().equalsIgnoreCase(activeSeasonName)) {
                return seasons[i];
            }
        }
        return null;
    }

    public String toDataText() {
        return "SALES_WINDOW_DAYS=" + salesWindowDays + "\n"
                + "SAFETY_MARGIN_DAYS=" + safetyMarginDays + "\n"
                + "REORDER_HORIZON_DAYS=" + reorderHorizonDays + "\n"
                + "ACTIVE_SEASON=" + activeSeasonName + "\n"
                + "SEASON_FROM_DATE=" + (seasonFromDate ? "1" : "0") + "\n";
    }

    /**
     * Copies the saved settings out of another object, leaving the demo date alone.
     *
     * <p>Used by the loader, which parses into a temporary object so a bad settings file
     * cannot leave the live settings half-applied. Only the fields that are actually
     * saved are copied: {@code demoDate} is a session-only demo override and must not be
     * overwritten by a file that never stored it.</p>
     *
     * @param source the settings that just parsed cleanly
     */
    public void copySavedValuesFrom(Settings source) {
        if (source == null) {
            throw new IllegalArgumentException("Cannot copy settings from null.");
        }
        if (source == this) {
            return;
        }
        this.salesWindowDays = source.salesWindowDays;
        this.safetyMarginDays = source.safetyMarginDays;
        this.reorderHorizonDays = source.reorderHorizonDays;
        this.activeSeasonName = source.activeSeasonName;
        this.seasonFromDate = source.seasonFromDate;
    }

    /** Every saved field, for a before/after comparison in a check program. */
    public String savedStateText() {
        return salesWindowDays + "/" + safetyMarginDays + "/" + reorderHorizonDays + "/"
                + activeSeasonName + "/" + seasonFromDate;
    }

    /**
     * Lead time plus safety margin, the number of days of stock that must be covered
     * before a delivery can arrive. Written once here so the display and the report
     * cannot disagree.
     *
     * @param leadTimeDays the item's supplier lead time
     */
    public int coverDaysFor(int leadTimeDays) {
        return leadTimeDays + safetyMarginDays;
    }

    /** System-wide default cover days, used where no item lead time is involved. */
    public int getLeadTimeBuffer() {
        return safetyMarginDays;
    }

    public int getSalesWindowDays() { return salesWindowDays; }
    public int getSafetyMarginDays() { return safetyMarginDays; }
    public int getReorderHorizonDays() { return reorderHorizonDays; }
    public String getActiveSeasonName() { return activeSeasonName; }
    public boolean isSeasonFromDate() { return seasonFromDate; }
    public LocalDate getDemoDate() { return demoDate; }

    public void setSalesWindowDays(int salesWindowDays) { this.salesWindowDays = DataText.atLeastOne(salesWindowDays, "Sales window"); }
    public void setSafetyMarginDays(int safetyMarginDays) { this.safetyMarginDays = DataText.nonNegative(safetyMarginDays, "Safety margin"); }
    public void setReorderHorizonDays(int reorderHorizonDays) { this.reorderHorizonDays = DataText.atLeastOne(reorderHorizonDays, "Reorder horizon"); }
    public void setActiveSeasonName(String activeSeasonName) { this.activeSeasonName = DataText.safeField(activeSeasonName, "active season"); }
    public void setSeasonFromDate(boolean seasonFromDate) { this.seasonFromDate = seasonFromDate; }
    public void setDemoDate(LocalDate demoDate) { this.demoDate = demoDate; }
}
