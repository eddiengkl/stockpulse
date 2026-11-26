package ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Calls the Gemini {@code generateContent} endpoint over HTTP.
 *
 * <p>The shape is fixed by Google's API discovery document
 * ({@code GET https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta}):</p>
 * <pre>
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * x-goog-api-key: &lt;key&gt;
 * {"contents":[{"role":"user","parts":[{"text":"..."}]}],"systemInstruction":{...}}
 * </pre>
 *
 * <p>The key travels in a header, never in the URL - a key in a query string is written to
 * access logs and to browser history. For the same reason, no exception thrown from here
 * ever contains the key: messages name the status code and the service's own error text,
 * which is what the user needs to act on anyway.</p>
 *
 * <p>Two timeouts, both finite. A café demo must not hang because a request never returns;
 * a slow model is worth waiting a few seconds for, an unresponsive one is not.</p>
 */
public final class GeminiClient implements LanguageModel {

    static final String HOST = "https://generativelanguage.googleapis.com";
    static final String API_VERSION = "v1beta";

    /** Long enough for a real reply, short enough that a demo never appears frozen. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String apiKey;
    private final String model;
    private final HttpClient client;

    public GeminiClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * The endpoint for a model name.
     *
     * <p>The API expects {@code models/gemini-3.8-flash}, while people write
     * {@code gemini-3.8-flash} in a properties file; both are accepted here rather than
     * making the format a trap.</p>
     */
    static String endpointFor(String model) {
        String name = model.startsWith("models/") ? model : "models/" + model;
        return HOST + "/" + API_VERSION + "/" + name + ":generateContent";
    }

    @Override
    public String generate(String requestJson) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpointFor(model)))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                // The status and the service's own explanation are exactly what the user
                // needs; the request body is not, so it is not quoted here. The status and
                // the machine-readable reason travel with the exception so the caller can
                // tell a retired model from a rejected key without matching English text.
                throw new ServiceHttpException(status, serviceStatus(response.body()),
                        serviceReason(response.body()),
                        "the AI service answered " + status + ": "
                                + serviceMessage(response.body()));
            }
            return response.body();
        } catch (InterruptedException e) {
            // Restore the flag: swallowing an interrupt makes a JVM that cannot shut down.
            Thread.currentThread().interrupt();
            throw new IOException("the request to the AI service was interrupted", e);
        }
    }

    /**
     * Pulls the sentence out of a Google error body.
     *
     * <p>Their errors look like {@code {"error":{"code":400,"message":"...","status":...}}}.
     * If the body is not that shape - a proxy page, an empty body - the raw text is cut to
     * a readable length rather than shown in full or dropped.</p>
     */
    /**
     * Reads {@code error.status}, such as {@code INVALID_ARGUMENT}, or null.
     *
     * <p>Null means "not stated" rather than a made-up default, so a caller can tell a
     * body that named the problem from one that only carried prose.</p>
     */
    static String serviceStatus(String body) {
        return serviceField(body, "status");
    }

    /**
     * Reads {@code error.details[].reason}, such as {@code API_KEY_INVALID}, or null.
     *
     * <p>Google puts the precise, stable cause here, inside an {@code ErrorInfo} entry in
     * {@code details}. It is far more reliable than the human sentence beside it, which is
     * what made a retired model look like a bad key.</p>
     */
    static String serviceReason(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            MiniJson.Value root = MiniJson.parse(body);
            MiniJson.Value error = root.optionalField("error");
            if (error == null) {
                return null;
            }
            MiniJson.Value details = error.optionalField("details");
            if (details == null || details.size() == 0) {
                return null;
            }
            for (int i = 0; i < details.size(); i++) {
                MiniJson.Value entry = details.index(i);
                MiniJson.Value reason = entry.optionalField("reason");
                if (reason != null && !reason.isNull()) {
                    return reason.asString();
                }
            }
            return null;
        } catch (MiniJson.Malformed e) {
            return null;
        }
    }

    private static String serviceField(String body, String field) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            MiniJson.Value error = MiniJson.parse(body).optionalField("error");
            if (error == null) {
                return null;
            }
            MiniJson.Value value = error.optionalField(field);
            return (value == null || value.isNull()) ? null : value.asString();
        } catch (MiniJson.Malformed e) {
            return null;
        }
    }

    private static String serviceMessage(String body) {
        if (body == null || body.isBlank()) {
            return "no explanation was given";
        }
        try {
            MiniJson.Value root = MiniJson.parse(body);
            MiniJson.Value error = root.optionalField("error");
            if (error != null) {
                MiniJson.Value message = error.optionalField("message");
                if (message != null) {
                    return message.asString();
                }
            }
        } catch (MiniJson.Malformed e) {
            // Not the documented shape; fall through to the raw text.
        }
        String oneLine = body.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "...";
    }
}
