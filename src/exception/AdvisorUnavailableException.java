package exception;

/**
 * Thrown when the AI advisor cannot produce a briefing.
 *
 * <p>Every reason lands here - no key configured, the model refusing the key, the network
 * being down, a reply that is not the JSON the API documents - because the caller has the
 * same job in each case: tell the user that the briefing is unavailable, say why in one
 * sentence, and offer the rule-based summary instead of inventing a briefing.</p>
 *
 * <p>It carries the reason as a fixed value rather than as free text, so the API can put
 * it in the response as a machine-readable field. The page needs to distinguish "you did
 * not configure a key" from "the service is down" to decide what to tell the user; parsing
 * an English sentence for that would be guesswork.</p>
 */
public class AdvisorUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    /** No key was found in the configuration file or the environment. */
    public static final String NO_KEY = "NoKey";
    /** The key was found and rejected by the AI service. */
    public static final String KEY_REJECTED = "KeyRejected";
    /**
     * The response identifies the model as unavailable.
     *
     * <p>Distinct from {@link #KEY_REJECTED} on purpose. The fixes are completely
     * different: one is a new name in the configuration, the other is a new key. Reporting
     * an unavailable model as a rejected key sends the user to the wrong place.</p>
     *
     * <p>This reason is used only when the response actually identifies the model as the
     * problem - a model-not-found style reason, or a message that names a model and says it
     * is gone. A generic 400 is {@link #REQUEST_REJECTED}, not this, because nothing in it
     * says which part of the request was wrong.</p>
     *
     * <p>It says nothing about the key. That the model was refused does not establish that
     * the key is accepted - the response simply did not raise it - so neither this
     * description nor the message built from it should imply otherwise.</p>
     */
    public static final String MODEL_UNAVAILABLE = "ModelUnavailable";
    /**
     * The service refused the request, and the response does not say why.
     *
     * <p>The honest answer to a 400 that names neither a credential nor a model problem, or
     * to any other status this program cannot interpret. The message says what is known -
     * the request was not accepted - and offers both plausible fixes without asserting
     * either: it must not claim the key was accepted, and must not instruct the user to
     * change one specific setting.</p>
     */
    public static final String REQUEST_REJECTED = "RequestRejected";
    /** The AI service could not be reached, or answered with a server error. */
    public static final String UNREACHABLE = "Unreachable";
    /** The AI service answered, but not with the JSON this program expects. */
    public static final String BAD_RESPONSE = "BadResponse";
    /** The AI service answered correctly but declined to write a briefing. */
    public static final String REFUSED = "Refused";

    private final String reason;
    private final String remediation;

    /**
     * @param reason      one of the constants above, echoed in the API response
     * @param message     what happened, in a sentence a café owner can read
     * @param remediation what a person can do about it; empty when nothing can be done
     */
    public AdvisorUnavailableException(String reason, String message, String remediation) {
        super(message);
        this.reason = reason;
        this.remediation = remediation == null ? "" : remediation;
    }

    /** Same, keeping the underlying failure for the console log. */
    public AdvisorUnavailableException(String reason, String message, String remediation,
                                       Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.remediation = remediation == null ? "" : remediation;
    }

    /** Which kind of failure this is, as a stable machine-readable value. */
    public String getReason() {
        return reason;
    }

    /** What the user can do about it, or empty when it is not in their hands. */
    public String getRemediation() {
        return remediation;
    }
}
