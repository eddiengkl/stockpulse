package ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the AI advisor finds its credentials and settings.
 *
 * <p><strong>The key is never stored in the source, in the data files, or in the
 * repository.</strong> It is read at start-up from two places, in this order:</p>
 * <ol>
 *   <li>{@code ai.properties} in the working directory - a file listed in
 *       {@code .gitignore}, with {@code ai.properties.example} committed beside it as the
 *       documented template;</li>
 *   <li>the environment variable {@code GEMINI_API_KEY}, so a machine can be set up
 *       without a file at all.</li>
 * </ol>
 *
 * <p>If neither is present, the advisor is simply unavailable: the website still runs, the
 * briefing reports that no key is configured, and nothing pretends otherwise. Not having a
 * key is a normal state for a demo, not an error that stops the program.</p>
 *
 * <p>The key is handed to the HTTP client as a request header rather than a query
 * parameter. A key in a URL ends up in access logs, proxy logs and browser history; a
 * header does not. For the same reason, this class never prints the key - {@link #describe()}
 * reports only whether one was found and which setting it came from.</p>
 */
public final class AdvisorConfig {

    /** Properties file read from the working directory. Listed in .gitignore. */
    public static final String FILE_NAME = "ai.properties";

    /** Environment variable used when the file does not name a key. */
    public static final String ENV_KEY = "GEMINI_API_KEY";

    /**
     * Default model. Overridable, because model names are retired over time.
     *
     * <p>{@code gemini-3.8-flash} is a stable model that outputs text, which is all this
     * feature needs. The previous default, {@code gemini-2.0-flash}, was shut down on
     * 1 June 2026 - Google's own model page says so and points at 3.8 Flash as the
     * replacement - so it is deliberately no longer named anywhere here.
     * Source: https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash</p>
     */
    public static final String DEFAULT_MODEL = "gemini-3.8-flash";

    /**
     * The placeholder {@code ai.properties.example} ships with.
     *
     * <p>Anyone who copies the example and fills in nothing keeps this value, and it is
     * not a key. Treating it as unset is what lets a {@code GEMINI_API_KEY} in the
     * environment still be used, instead of the program sending the literal text
     * "PASTE-YOUR-KEY-HERE" to Google and reporting that the user's key was rejected.</p>
     */
    public static final String EXAMPLE_PLACEHOLDER = "PASTE-YOUR-KEY-HERE";

    private static final String KEY_PROPERTY = "gemini.apiKey";
    private static final String MODEL_PROPERTY = "gemini.model";

    private final String apiKey;
    private final String model;
    private final String source;

    private AdvisorConfig(String apiKey, String model, String source) {
        this.apiKey = apiKey;
        this.model = model;
        this.source = source;
    }

    /**
     * Reads the configuration, tolerating every way it can be absent.
     *
     * <p>A malformed {@code ai.properties} is not fatal either: the advisor reports the
     * missing key and the rest of the site is untouched, which is better than refusing to
     * start a café inventory system because an optional AI feature is misconfigured.</p>
     */
    public static AdvisorConfig load() {
        return load(Path.of(FILE_NAME), System.getenv(ENV_KEY));
    }

    /**
     * Reads from an explicit file and environment value.
     *
     * <p>Separate from {@link #load()} so a check program can prove the precedence rules and
     * the "no key" path without setting real environment variables for the whole machine,
     * which a running JVM cannot do.</p>
     *
     * @param file      the properties file to read, or null to skip it
     * @param envKey    the value the environment variable would hold, or null
     */
    public static AdvisorConfig load(Path file, String envKey) {
        String key = "";
        String model = DEFAULT_MODEL;
        String source = "none";

        if (file != null && Files.isRegularFile(file)) {
            Settings values;
            try {
                values = readProperties(file);
            } catch (IOException e) {
                // Unreadable is the same as absent for our purposes; the reason is not
                // shown to the user because it names a path they can already see.
                values = null;
            }
            if (values != null) {
                String fromFile = values.get(KEY_PROPERTY);
                if (isUsableKey(fromFile)) {
                    key = fromFile.trim();
                    source = FILE_NAME;
                }
                String fromFileModel = values.get(MODEL_PROPERTY);
                if (fromFileModel != null && !fromFileModel.isBlank()) {
                    model = fromFileModel.trim();
                }
            }
        }

        if (key.isEmpty() && isUsableKey(envKey)) {
            key = envKey.trim();
            source = "environment variable " + ENV_KEY;
        }

        return new AdvisorConfig(key, model, source);
    }

    /**
     * True when a value could be a real key rather than an absent one.
     *
     * <p>Three things count as absent: nothing, whitespace, and the placeholder the example
     * file ships with. The placeholder matters at the file layer specifically - a user who
     * copies the example, sets no key in it, and exports {@code GEMINI_API_KEY} expects the
     * environment to be used. Without this the shipped placeholder would win, be sent to
     * Google, and be rejected as an invalid key - a confusing way to say "you left the
     * template alone". (A bogus key was observed live to produce "API key not valid"; the
     * placeholder itself was not sent to Google during checks, because the whole point is
     * that it never is.)</p>
     */
    static boolean isUsableKey(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (EXAMPLE_PLACEHOLDER.equals(candidate.trim())) {
            return false;
        }
        // The placeholders in older copies of the example, and the XML angle-bracket form.
        return !candidate.trim().equalsIgnoreCase("<your-key-here>");
    }

    /**
     * Reads the small subset of the properties format this project needs.
     *
     * <p>The full {@code java.util.Properties} format brings escapes, continuations and
     * line-ending rules that no one here needs; keeping it to {@code key=value} means the
     * file can be explained in one sentence and cannot surprise anyone. Blank lines and
     * lines starting with {@code #} are ignored.</p>
     *
     * <p>Settings are returned as two parallel arrays - keys and values - rather than a
     * {@code Map}, because this project keeps its records in plain arrays with counters and
     * does not use the Collections Framework.</p>
     */
    private static Settings readProperties(Path file) throws IOException {
        String[] keys = new String[16];
        String[] values = new String[16];
        int count = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (count == keys.length) {
                // Doubling keeps the arrays small for a two-line file and still bounded.
                keys = grow(keys);
                values = grow(values);
            }
            keys[count] = trimmed.substring(0, equals).trim();
            values[count] = trimmed.substring(equals + 1).trim();
            count++;
        }
        return new Settings(keys, values, count);
    }

    private static String[] grow(String[] array) {
        String[] bigger = new String[array.length * 2];
        for (int i = 0; i < array.length; i++) {
            bigger[i] = array[i];
        }
        return bigger;
    }

    /** A tiny key/value list: two arrays and a count, like every other store here. */
    private static final class Settings {
        private final String[] keys;
        private final String[] values;
        private final int count;

        Settings(String[] keys, String[] values, int count) {
            this.keys = keys;
            this.values = values;
            this.count = count;
        }

        /** @return the value for a key, or null when the file did not set it */
        String get(String key) {
            for (int i = 0; i < count; i++) {
                if (keys[i].equalsIgnoreCase(key)) {
                    return values[i];
                }
            }
            return null;
        }
    }

    /** True when a key was found, so the advisor can try to call the model. */
    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    /**
     * The key itself. Package-private on purpose: only {@link GeminiClient} in this package
     * should ever hold it, so no other class can accidentally log or return it. {@link Wire}
     * builds the client through {@link #newClient()}.
     */
    String apiKey() {
        return apiKey;
    }

    /**
     * Builds the model client for the configured key, or null when there is no key.
     *
     * <p>Returning null instead of throwing keeps "no key" a normal state: the endpoint still
     * answers, with a rule-based summary that is labelled as one.</p>
     */
    public LanguageModel newClient() {
        return hasKey() ? new GeminiClient(apiKey, model) : null;
    }

    /** The model name to ask for, such as {@code gemini-3.8-flash}. */
    public String model() {
        return model;
    }

    /** Which setting the key came from, for the console and the API - never the key. */
    public String source() {
        return source;
    }

    /** One line for the start-up banner: where the key came from, never what it is. */
    public String describe() {
        if (!hasKey()) {
            return "AI advisor: no key configured (" + FILE_NAME + " or " + ENV_KEY
                    + "); briefings will report that the service is unavailable";
        }
        return "AI advisor: key from " + source + ", model " + model;
    }
}
