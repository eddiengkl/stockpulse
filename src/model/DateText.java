package model;

import java.time.LocalDate;

/**
 * Formats and parses dates as dd/MM/yyyy, the format used in every data file and in
 * the UI. Parsing is strict so a typo is reported instead of silently becoming a
 * different date.
 */
public final class DateText {

    public static final String PATTERN = "dd/MM/yyyy";

    private static final java.time.format.DateTimeFormatter FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern(PATTERN);
    // "uuuu" (proleptic year) rather than "yyyy" (year-of-era): with ResolverStyle.STRICT
    // a "yyyy" pattern cannot resolve to a LocalDate because the era is missing.
    private static final java.time.format.DateTimeFormatter STRICT =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    private DateText() {
    }

    /** @return the date as dd/MM/yyyy */
    public static String format(LocalDate date) {
        return date == null ? "-" : FORMATTER.format(date);
    }

    /**
     * Parses dd/MM/yyyy.
     *
     * @throws IllegalArgumentException when the text is not a real date in that format
     */
    public static LocalDate parse(String text) {
        String trimmed = (text == null) ? "" : text.trim();
        try {
            return LocalDate.parse(trimmed, STRICT);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Date '" + trimmed + "' is not a valid dd/MM/yyyy date.", e);
        }
    }

    /** Parses a dash as "no date", for optional expiry dates. */
    public static LocalDate parseOptional(String text) {
        String trimmed = (text == null) ? "" : text.trim();
        return trimmed.isEmpty() || trimmed.equals("-") ? null : parse(trimmed);
    }
}
