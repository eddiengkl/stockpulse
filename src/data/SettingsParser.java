package data;

import exception.DataFileException;
import model.Settings;

/**
 * Reads settings.txt, which unlike the other files is a set of {@code KEY=value} lines
 * rather than pipe-delimited records.
 *
 * <pre>
 * SALES_WINDOW_DAYS=14
 * SAFETY_MARGIN_DAYS=1
 * REORDER_HORIZON_DAYS=7
 * ACTIVE_SEASON=Dry
 * SEASON_FROM_DATE=0
 * </pre>
 *
 * <p>One line at a time, into a caller-supplied {@link Settings} object. The loader
 * passes a <em>temporary</em> object, so a bad setting cannot leave the live settings
 * half-applied: the temporary object is discarded unless every file parsed.</p>
 *
 * <p>An unknown key is reported rather than ignored. A silently ignored typo is exactly
 * how a setting appears to save and then does not apply.</p>
 */
public final class SettingsParser {

    public static final String KEY_WINDOW = "SALES_WINDOW_DAYS";
    public static final String KEY_SAFETY = "SAFETY_MARGIN_DAYS";
    public static final String KEY_HORIZON = "REORDER_HORIZON_DAYS";
    public static final String KEY_ACTIVE_SEASON = "ACTIVE_SEASON";
    public static final String KEY_SEASON_FROM_DATE = "SEASON_FROM_DATE";

    private SettingsParser() {
    }

    /**
     * Parses one {@code KEY=value} line into {@code target}.
     *
     * <p>Missing keys simply keep whatever {@code target} already holds, so an older file
     * still loads after a new setting is added, and a new file still loads when an old
     * setting is removed.</p>
     *
     * @throws DataFileException when the line is not {@code KEY=value}, the key is
     *                           unknown, or the value is not acceptable
     */
    public static void applyLine(String raw, int lineNumber, String fileName, Settings target)
            throws DataFileException {
        int equals = raw.indexOf('=');
        if (equals < 0) {
            throw new DataFileException(fileName, lineNumber,
                    "expected KEY=value but found no '=' in '" + raw + "'");
        }
        String key = raw.substring(0, equals).trim();
        String value = raw.substring(equals + 1).trim();
        if (key.isEmpty()) {
            throw new DataFileException(fileName, lineNumber,
                    "the setting name before '=' is empty");
        }
        applyOne(key, value, lineNumber, fileName, target);
    }

    private static void applyOne(String key, String value, int lineNumber, String fileName,
                                 Settings target) throws DataFileException {
        try {
            if (KEY_WINDOW.equals(key)) {
                target.setSalesWindowDays(toInt(value, key, lineNumber, fileName));
            } else if (KEY_SAFETY.equals(key)) {
                target.setSafetyMarginDays(toInt(value, key, lineNumber, fileName));
            } else if (KEY_HORIZON.equals(key)) {
                target.setReorderHorizonDays(toInt(value, key, lineNumber, fileName));
            } else if (KEY_ACTIVE_SEASON.equals(key)) {
                target.setActiveSeasonName(value);
            } else if (KEY_SEASON_FROM_DATE.equals(key)) {
                target.setSeasonFromDate(toFlag(value, key, lineNumber, fileName));
            } else {
                throw new DataFileException(fileName, lineNumber,
                        "unknown setting '" + key + "'. Known settings: " + KEY_WINDOW + ", "
                                + KEY_SAFETY + ", " + KEY_HORIZON + ", " + KEY_ACTIVE_SEASON + ", "
                                + KEY_SEASON_FROM_DATE);
            }
        } catch (IllegalArgumentException e) {
            // The Settings setters validate their own ranges.
            throw new DataFileException(fileName, lineNumber, e.getMessage(), e);
        }
    }

    private static int toInt(String value, String key, int lineNumber, String fileName)
            throws DataFileException {
        if (value.isEmpty()) {
            throw new DataFileException(fileName, lineNumber, "setting '" + key + "' has no value");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new DataFileException(fileName, lineNumber,
                    "setting '" + key + "' should be a whole number but was '" + value + "'", e);
        }
    }

    private static boolean toFlag(String value, String key, int lineNumber, String fileName)
            throws DataFileException {
        if ("1".equals(value) || "true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("0".equals(value) || "false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new DataFileException(fileName, lineNumber,
                "setting '" + key + "' should be 0 or 1 but was '" + value + "'");
    }
}
