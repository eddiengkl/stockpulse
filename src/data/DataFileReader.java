package data;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import exception.DataFileException;

/**
 * Reads a data file one line at a time and hands each record to a caller-supplied
 * handler.
 *
 * <p>There is no line buffer anywhere: the file is read, validated and turned into model
 * objects in a single pass, so no {@code List} or other collection is needed to hold the
 * lines. The handler decides what to do with each record.</p>
 *
 * <p>The reader adds the file name and line number to every {@link DataFileException},
 * so a parser only has to say <em>what</em> is wrong, never <em>where</em>.</p>
 *
 * <p>The reader never writes anything. If a line cannot be understood the pass stops with
 * the exception: skipping it would mean the program starts with a partial dataset and
 * then saves it back over the good file, which is the silent data loss the design is
 * trying to avoid.</p>
 */
public final class DataFileReader {

    private final Path directory;

    /** @param directory folder holding the data files */
    public DataFileReader(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    public Path pathOf(String fileName) {
        return directory.resolve(fileName);
    }

    public boolean exists(String fileName) {
        return Files.isRegularFile(pathOf(fileName));
    }

    /**
     * Handles one record. Implemented by the loader for each file.
     */
    @FunctionalInterface
    public interface LineHandler {
        /**
         * @param line       the record text, trimmed of surrounding whitespace
         * @param lineNumber one-based line number in the original file
         * @throws DataFileException when the record is not usable
         */
        void handle(String line, int lineNumber) throws DataFileException;
    }

    /**
     * Reads a file, calling {@code handler} once per meaningful line.
     *
     * <p>Blank lines and lines starting with {@code #} are skipped, so a handler can
     * assume it is handed a real record. A UTF-8 BOM on line 1 (written by some text
     * editors) is stripped.</p>
     *
     * <p>A missing file is an error, not an empty file. Seeding only happens when
     * <em>none</em> of the data files exist, so a partially missing data folder means
     * something was deleted or a save was interrupted. Loading it as empty data and then
     * saving would wipe the records the other files still hold.</p>
     *
     * @param fileName the file to read, e.g. {@link DataFiles#ITEMS}
     * @param handler  called for every record, in file order
     * @return how many records were handled
     * @throws DataFileException when the file is missing, unreadable, or a record is bad
     */
    public int forEachLine(String fileName, LineHandler handler) throws DataFileException {
        Path path = pathOf(fileName);
        if (!Files.isRegularFile(path)) {
            throw new DataFileException(fileName, 0,
                    "is missing. Restore it from a backup, or delete every file in the "
                            + DataFiles.DATA_DIR + " folder to start again from the sample data.");
        }
        int handled = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String raw;
            int lineNumber = 0;
            while ((raw = reader.readLine()) != null) {
                lineNumber++;
                // Strip a UTF-8 BOM that some editors write at the start of the file.
                if (lineNumber == 1 && !raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                    raw = raw.substring(1);
                }
                if (DataFiles.isIgnorable(raw)) {
                    continue;
                }
                handler.handle(raw.trim(), lineNumber);
                handled++;
            }
        } catch (IOException e) {
            throw new DataFileException(fileName, 0,
                    "could not be read (" + e.getMessage() + ")", e);
        }
        return handled;
    }

    /**
     * Splits a record into fields on {@code '|'}.
     *
     * <p>Uses {@code split(..., -1)} so trailing empty fields are kept: a waste line with
     * a blank reason, or a sale line with no batch draws, must still count as the right
     * number of fields rather than silently losing the last one.</p>
     *
     * @throws DataFileException when the field count is wrong
     */
    public static String[] split(String line, int lineNumber, String fileName, int expectedFields)
            throws DataFileException {
        String[] parts = line.split("\\|", -1);
        if (parts.length != expectedFields) {
            throw new DataFileException(fileName, lineNumber,
                    "expected " + expectedFields + " fields separated by '|' but found "
                            + parts.length + " (" + line + ")");
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * Rejects an empty required field, naming the field and the line.
     *
     * @throws DataFileException when the field is blank
     */
    public static String requireField(String value, int lineNumber, String fileName,
                                      String fieldName) throws DataFileException {
        if (value == null || value.trim().isEmpty()) {
            throw new DataFileException(fileName, lineNumber,
                    "field '" + fieldName + "' cannot be empty");
        }
        return value.trim();
    }
}
