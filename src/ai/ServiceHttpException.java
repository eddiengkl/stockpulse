package ai;

import java.io.IOException;

/**
 * An HTTP failure from the AI service, carrying the facts that decide how to report it.
 *
 * <p>It exists so that {@link AdvisorService} can report a failure from what the response
 * states rather than from the sentence beside it. A status code and the service's own
 * machine-readable {@code status} and {@code reason} fields are stable values that can be
 * compared; the human message is prose, and reading it was how earlier rounds guessed a
 * cause and got it wrong. This class carries the numeric status, the {@code status} string
 * and the {@code reason} string so that {@code AdvisorService} can classify from those.</p>
 *
 * <p>How the message is used depends on what else is available. When a structured
 * {@code status} or {@code reason} is present, the classifier checks those first and also
 * inspects the message for explicit model evidence, so a response that names a model in
 * prose is still recognised. When there are no structured HTTP fields at all - a plain
 * {@link IOException} from a transport failure, a proxy, or a fake model - the message is
 * the only evidence there is, and classification rests on it.</p>
 *
 * <p>What this class asserts is how the program classifies a response, not how any
 * particular provider replies. The exact {@code generateContent} error shapes have not been
 * confirmed against a live successful-key request, so nothing here should be read as a
 * description of one.</p>
 *
 * <p>It extends {@link IOException} so the {@link LanguageModel} seam stays one method that
 * throws a familiar type; a fake model in the checks can throw a plain {@code IOException}
 * and still be handled correctly.</p>
 */
public final class ServiceHttpException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String status;
    private final String reason;

    /**
     * @param statusCode the HTTP status, such as 400 or 429
     * @param status     the service's status string, such as {@code INVALID_ARGUMENT},
     *                   or null when the body did not name one
     * @param reason     the service's {@code ErrorInfo.reason}, such as
     *                   {@code API_KEY_INVALID}, or null
     * @param message    a human sentence for the log and the API, never the key
     */
    public ServiceHttpException(int statusCode, String status, String reason, String message) {
        super(message);
        this.statusCode = statusCode;
        this.status = status;
        this.reason = reason;
    }

    /** The HTTP status code the service returned. */
    public int getStatusCode() {
        return statusCode;
    }

    /** The service's status string, such as INVALID_ARGUMENT, or null. */
    public String getStatus() {
        return status;
    }

    /** The service's machine-readable reason, such as API_KEY_INVALID, or null. */
    public String getReason() {
        return reason;
    }

    /** True when the reason says the credentials were refused. */
    public boolean isCredentialProblem() {
        return "API_KEY_INVALID".equals(reason)
                || "API_KEY_EXPIRED".equals(reason)
                || "CONSUMER_SUSPENDED".equals(reason);
    }

    /**
     * True when the response clearly identifies the model as the problem.
     *
     * <p>Deliberately strict. It fires only on evidence that names a model: a
     * model-not-found style reason, a {@code NOT_FOUND} status that the body ties to a
     * model, or a sentence that mentions a model and says it is missing, shut down, or
     * unsupported. A bare {@code 404}, or the generic phrase "is not found" with no model
     * mentioned, does not qualify - a wrong endpoint also produces those, and blaming the
     * model would be a guess dressed as a diagnosis.</p>
     *
     * <p><strong>The reason strings are supported inputs, not verified output.</strong>
     * {@code MODEL_NOT_FOUND} and its companions are recognised so that a response carrying
     * one is classified correctly, but their exact shape for this {@code generateContent}
     * endpoint has not been confirmed against a live response: without a valid key, the
     * service rejects the request before it resolves the model, so that path has never been
     * reached. The Google error reference consulted while writing this documents a
     * model-not-found code for a different API, so it is not a direct source for this one.
     * The string checks are a classification rule, not a claim about what Google sends.</p>
     *
     * <p>Model evidence is consulted only after credentials, so a genuine
     * {@code API_KEY_INVALID} never reaches here even when the sentence also mentions a
     * model.</p>
     */
    public boolean isModelProblem() {
        if (isCredentialProblem()) {
            return false;
        }
        if ("MODEL_NOT_FOUND".equals(reason) || "MODEL_DEPRECATED".equals(reason)
                || "MODEL_RETIRED".equals(reason) || "UNSUPPORTED_MODEL".equals(reason)) {
            return true;
        }
        String text = getMessage() == null ? "" : getMessage().toLowerCase();
        // Sentences that name the model itself.
        if (text.contains("model not found") || text.contains("unknown model")
                || text.contains("unsupported model")) {
            return true;
        }
        // A sentence naming a model resource and saying it is gone. The two tests are
        // bracketed separately rather than mixed in one && / || chain, because operator
        // precedence in such a chain is how this kind of check goes wrong silently.
        boolean namesModel = text.contains("models/") || text.contains("the model")
                || text.contains("this model");
        boolean saysGone = text.contains("not found") || text.contains("shut down")
                || text.contains("no longer available") || text.contains("not supported")
                || text.contains("deprecated");
        if (namesModel && saysGone) {
            return true;
        }
        // "NOT_FOUND" alone is not enough - a wrong path also returns it. It counts only
        // when the sentence ties the failure to a model.
        boolean notFound = "NOT_FOUND".equals(status) || "NOT_FOUND".equals(reason);
        return notFound && text.contains("model");
    }

    /**
     * True when nothing in the response says whether the key or the model was at fault.
     *
     * <p>The caller uses this to pick the generic reason, so a 400 that is really about a
     * malformed request is not forced into either bucket.</p>
     */
    public boolean isUnexplained() {
        return !isCredentialProblem() && !isModelProblem();
    }
}
