package model;

import exception.InvalidSeasonException;

/**
 * A named time of year with a month range, e.g. "Dry" from May to September.
 *
 * <p>The season holds only its name and months. The demand multiplier lives on each
 * item (see {@link StockItem#getSeasonalMultiplier()}), so "iced syrup is 35% busier
 * in the dry season" is a property of the syrup, not of the season.</p>
 *
 * <p>A rain period that runs from October to February is expressed with
 * {@code startMonth = 10} and {@code endMonth = 2} and wraps across the new year.
 * {@code startMonth > endMonth} is therefore valid. A season whose start and end month
 * are the same covers that one month only.</p>
 */
public class Season {

    private String name;
    private int startMonth;   // 1..12
    private int endMonth;     // 1..12, may be lower than startMonth when wrapping

    /**
     * @param name       display name, e.g. "Dry"
     * @param startMonth 1..12
     * @param endMonth   1..12; lower than startMonth wraps past December, equal to
     *                   startMonth means a single-month season
     * @throws InvalidSeasonException when a month is outside 1..12 or the name is blank
     */
    public Season(String name, int startMonth, int endMonth) throws InvalidSeasonException {
        this.name = checkName(name);
        this.startMonth = checkMonth(startMonth, "start");
        this.endMonth = checkMonth(endMonth, "end");
    }

    public void setName(String name) throws InvalidSeasonException {
        this.name = checkName(name);
    }

    /**
     * Validates and stores the month range.
     *
     * @throws InvalidSeasonException when a month is outside 1..12
     */
    public void setMonths(int startMonth, int endMonth) throws InvalidSeasonException {
        this.startMonth = checkMonth(startMonth, "start");
        this.endMonth = checkMonth(endMonth, "end");
    }

    /** Static so constructing a Season cannot pass a half-built object to a validation method. */
    private static String checkName(String name) throws InvalidSeasonException {
        if (name == null || name.trim().isEmpty()) {
            throw new InvalidSeasonException("Season name cannot be empty.");
        }
        String trimmed = name.trim();
        if (trimmed.indexOf('|') >= 0 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new InvalidSeasonException(
                    "Season name cannot contain '|' or a line break: '" + trimmed + "'.");
        }
        return trimmed;
    }

    private static int checkMonth(int month, String which) throws InvalidSeasonException {
        if (month < 1 || month > 12) {
            throw new InvalidSeasonException(
                    "Season " + which + " month must be 1-12 but was " + month + ".");
        }
        return month;
    }

    /**
     * Whether the given month falls inside this season, wrapping across the new year
     * when needed. Example: start 10, end 2 covers 10, 11, 12, 1 and 2. A season whose
     * start equals its end covers that month alone.
     *
     * @param month 1..12
     */
    public boolean covers(int month) {
        if (startMonth == endMonth) {
            return month == startMonth;
        }
        if (startMonth < endMonth) {
            return month >= startMonth && month <= endMonth;
        }
        return month >= startMonth || month <= endMonth;
    }

    /** Number of months covered, counting the wrap across December correctly. */
    public int monthCount() {
        if (startMonth == endMonth) {
            return 1;
        }
        return (startMonth < endMonth)
                ? endMonth - startMonth + 1
                : (13 - startMonth) + endMonth;
    }

    /** Human wording of the month range, e.g. "Oct - Feb" or "May". */
    public String monthRangeText() {
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        if (startMonth == endMonth) {
            return months[startMonth - 1];
        }
        return months[startMonth - 1] + " - " + months[endMonth - 1];
    }

    /** "Dry (May - Sep)". */
    public String describe() {
        return name + " (" + monthRangeText() + ")";
    }

    /**
     * An independent copy with the same name and months.
     *
     * <p>Seasons are mutable - {@link #setMonths(int, int)} changes them in place - so a
     * snapshot that merely held the same object would record the values <em>after</em> a
     * failed change was applied. A real copy is what lets a failed save put the old month
     * range back.</p>
     *
     * <p>The constructor validates the name and months again. That cannot fail here,
     * because both values are already valid on the source object; any exception would
     * mean the source was corrupted, which is worth knowing about rather than hiding.</p>
     */
    public Season copy() {
        try {
            return new Season(name, startMonth, endMonth);
        } catch (InvalidSeasonException e) {
            // Only reachable if the source object was modified outside its setters.
            throw new IllegalStateException(
                    "A season could not be copied: " + describe(), e);
        }
    }

    public String toDataLine() {
        return name + "|" + startMonth + "|" + endMonth;
    }

    public String getName() { return name; }
    public int getStartMonth() { return startMonth; }
    public int getEndMonth() { return endMonth; }
}
