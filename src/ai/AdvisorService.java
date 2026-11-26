package ai;

import java.io.IOException;
import java.time.LocalDate;

import exception.AdvisorUnavailableException;
import model.NumberText;
import model.RiskFlag;
import model.Season;
import service.ItemAnalysis;
import service.ReadService;

/**
 * The AI advisor: turns the dashboard's own figures into a short written briefing.
 *
 * <p>The division of labour is the important part. <strong>Java decides every number and
 * every priority</strong> - the ranking, the velocities, the days of cover, the reorder
 * quantities all come from {@code DashboardService}. The model is given those figures as a
 * fact sheet and asked only to put them into plain language for a café owner. It is never
 * asked to compute anything, so a wrong answer can only be a wrong sentence, not a wrong
 * business rule. This is the same reason {@code DashboardService} exists: a rule that lives
 * in a prompt is a rule nobody tested.</p>
 *
 * <p>The model's reply is treated as untrusted input. It is parsed with a strict reader,
 * its shape is checked, its length is capped, and anything unexpected becomes
 * {@link AdvisorUnavailableException} - the API then reports the briefing as unavailable
 * and offers the rule-based summary instead. Nothing from the model reaches the page
 * unvalidated, and no failure is ever papered over with invented text.</p>
 */
public final class AdvisorService {

    /** The model is asked for this many items, so the fact sheet stays one screen long. */
    private static final int FACT_SHEET_ITEMS = 5;

    /** A briefing longer than this is truncated: the page has room for a paragraph. */
    static final int MAX_BRIEFING_CHARS = 1200;

    /**
     * The instruction. It restates the constraint that matters: use only the numbers given,
     * do not invent figures, and do not give advice about anything but perishable stock.
     */
    static final String SYSTEM_INSTRUCTION =
            "You are the stock advisor inside StockPulse, a stock system for a small café."
            + " You are given a fact sheet that Java has already computed from the café's"
            + " own sales, stock and waste records."
            + " Write a short briefing for the café owner: two or three short paragraphs,"
            + " plain English, no headings, no bullet symbols, no markdown."
            + " Use ONLY the numbers and item names in the fact sheet. Never invent a figure,"
            + " an item or a date. Never say you are an AI or mention the fact sheet."
            + " Lead with what will cost the owner money soonest, and end with one concrete"
            + " action for today. If the fact sheet lists no warnings, say the café is in a"
            + " steady position and suggest what to watch.";

    private final ReadService read;
    private final AdvisorConfig config;
    private final LanguageModel model;

    /**
     * @param read   the read model; the advisor only ever reads from it
     * @param config where the key and model name come from
     * @param model  the transport, or null when the advisor is configured but has no key -
     *               in which case {@link #briefing()} reports that instead of calling out
     */
    public AdvisorService(ReadService read, AdvisorConfig config, LanguageModel model) {
        this.read = read;
        this.config = config;
        this.model = model;
    }

    /** True when a key was found, so a briefing can be attempted at all. */
    public boolean isConfigured() {
        return config.hasKey() && model != null;
    }

    /** The model name reported to the page, whether or not a key is present. */
    public String modelName() {
        return config.model();
    }

    /** The fact sheet: how many items it described, for the response. */
    public int factSheetItems() {
        ItemAnalysis[] analyses = read.analyses();
        return Math.min(analyses.length, FACT_SHEET_ITEMS);
    }

    /**
     * Asks the model for a briefing.
     *
     * @return the model's text, trimmed; never empty
     * @throws AdvisorUnavailableException when no key is configured, the call fails, or the
     *                                     reply is not usable - with the reason distinguished
     */
    public String briefing() throws AdvisorUnavailableException {
        if (!config.hasKey()) {
            throw new AdvisorUnavailableException(AdvisorUnavailableException.NO_KEY,
                    "No AI key is configured, so no briefing can be requested.",
                    "Put gemini.apiKey in " + AdvisorConfig.FILE_NAME + ", or set the "
                            + AdvisorConfig.ENV_KEY + " environment variable, then restart.");
        }
        if (model == null) {
            throw new AdvisorUnavailableException(AdvisorUnavailableException.NO_KEY,
                    "No AI client is available, so no briefing can be requested.", "");
        }

        String reply;
        try {
            reply = model.generate(requestJson());
        } catch (IOException e) {
            throw fromTransport(e);
        }
        return readReply(reply);
    }

    /** The rule-based summary. Always available, always labelled RULES by the caller. */
    public String ruleSummary() {
        return RuleSummary.summarise(read);
    }

    // ------------------------------------------------------------ fact sheet

    /**
     * Builds the request body.
     *
     * <p>The JSON is written by hand rather than through {@code Json} because the body needs
     * nested {@code systemInstruction} and {@code contents} objects in a fixed shape, and a
     * single-purpose writer here is easier to verify against Google's documented request
     * than a general builder would be.</p>
     */
    String requestJson() {
        StringBuilder json = new StringBuilder(1024);
        json.append("{\"systemInstruction\":{\"parts\":[{\"text\":")
                .append(quote(SYSTEM_INSTRUCTION))
                .append("}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":")
                .append(quote(factSheet()))
                .append("}]}],\"generationConfig\":{\"temperature\":0.4,\"maxOutputTokens\":600}}");
        return json.toString();
    }

    /**
     * The fact sheet the model reads.
     *
     * <p>It is written as text rather than as nested JSON on purpose: a model reads a
     * labelled list more reliably than a structure whose keys it has to interpret, and the
     * labels are the same words the dashboard uses, so a sentence in the briefing can be
     * traced back to a line here.</p>
     */
    String factSheet() {
        LocalDate today = read.today();
        StringBuilder sheet = new StringBuilder();
        sheet.append("Date: ").append(today).append('\n');

        Season season = read.activeSeason();
        if (season != null) {
            sheet.append("Active season: ").append(season.getName())
                    .append(" (").append(season.getStartMonth()).append('-')
                    .append(season.getEndMonth()).append(")\n");
        } else {
            sheet.append("Active season: none\n");
        }

        RiskFlag[] flags = read.flags();
        sheet.append("Warnings raised by the system: ")
                .append(flags.length).append('\n');
        int limit = Math.min(flags.length, 6);
        for (int i = 0; i < limit; i++) {
            sheet.append("  - ").append(flags[i].getItemId())
                    .append(' ').append(flags[i].getType())
                    .append(": ").append(flags[i].getReason()).append('\n');
        }
        if (flags.length > limit) {
            sheet.append("  - and ").append(flags.length - limit).append(" more\n");
        }

        sheet.append("Items, highest priority first:\n");
        ItemAnalysis[] analyses = read.analyses();
        int items = Math.min(analyses.length, FACT_SHEET_ITEMS);
        for (int i = 0; i < items; i++) {
            ItemAnalysis analysis = analyses[i];
            sheet.append("  - ").append(analysis.item.getName())
                    .append(" (").append(analysis.itemId()).append("), ").append(analysis.item.getUnit())
                    .append(": stock ").append(NumberText.quantity(analysis.stockOnHand))
                    .append(", daily demand ").append(NumberText.quantity(analysis.seasonalVelocity))
                    .append(", days of cover ")
                    .append(analysis.neverRunsOut() ? "no sales recorded"
                            : NumberText.quantity(analysis.daysOfCover))
                    .append(", reorder point ").append(NumberText.quantity(analysis.reorderPoint));
            if (!analysis.noExpiry()) {
                sheet.append(", days until expiry ").append(analysis.daysUntilExpiry)
                        .append(", ").append(analysis.stockDescription);
            }
            sheet.append('\n');
        }

        sheet.append("Stock value at cost: RM")
                .append(NumberText.money(totalStockValueAtCost(analyses)))
                .append('\n');
        return sheet.toString();
    }

    /** Total stock value at cost, summed from the analyses already computed for the sheet. */
    private static double totalStockValueAtCost(ItemAnalysis[] analyses) {
        double total = 0;
        for (int i = 0; i < analyses.length; i++) {
            total += analyses[i].stockValueAtCost;
        }
        return total;
    }

    /**
     * Escapes a string for a JSON string literal.
     *
     * <p>Only the characters JSON requires plus the control range; item names and supplier
     * names may hold quotes, and an unescaped quote here would produce a request the service
     * rejects as malformed - a failure caused entirely by our own writer.</p>
     */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            out.append('0');
                        }
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    // ---------------------------------------------------------- reply check

    /**
     * Extracts the briefing text from Google's response envelope.
     *
     * <p>Every documented way a reply can be unusable is checked here rather than left to
     * fail later: a blocked prompt ({@code promptFeedback.blockReason}), no candidates, a
     * candidate that stopped for a reason other than {@code STOP}, and an empty text part.
     * Each of those is a different sentence for the user, because "your key was rejected"
     * and "the model declined to answer" call for different actions.</p>
     */
    static String readReply(String body) throws AdvisorUnavailableException {
        MiniJson.Value root;
        try {
            root = MiniJson.parse(body);
        } catch (MiniJson.Malformed e) {
            throw new AdvisorUnavailableException(AdvisorUnavailableException.BAD_RESPONSE,
                    "The AI service replied with something this program could not read ("
                            + e.getMessage() + ").",
                    "This is usually temporary. Try again, or check that "
                            + AdvisorConfig.FILE_NAME + " names a model that exists.");
        }

        try {
            MiniJson.Value feedback = root.optionalField("promptFeedback");
            if (feedback != null) {
                MiniJson.Value blocked = feedback.optionalField("blockReason");
                if (blocked != null && !blocked.isNull()) {
                    throw new AdvisorUnavailableException(AdvisorUnavailableException.REFUSED,
                            "The AI service declined to answer this request ("
                                    + blocked.asString() + ").",
                            "Nothing was changed. The dashboard's own summary is shown instead.");
                }
            }

            MiniJson.Value candidates = root.field("candidates");
            if (candidates.size() == 0) {
                throw new AdvisorUnavailableException(AdvisorUnavailableException.REFUSED,
                        "The AI service returned no briefing at all.",
                        "Try again in a moment; the dashboard's own summary is shown instead.");
            }

            MiniJson.Value first = candidates.index(0);
            MiniJson.Value finish = first.optionalField("finishReason");
            if (finish != null && !finish.isNull()) {
                String reason = finish.asString();
                if (!"STOP".equals(reason)) {
                    throw new AdvisorUnavailableException(AdvisorUnavailableException.REFUSED,
                            "The AI service stopped writing before it finished ("
                                    + reason + ").",
                            "Try again; a shorter request may help. The dashboard's own"
                                    + " summary is shown instead.");
                }
            }

            MiniJson.Value parts = first.field("content").field("parts");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                MiniJson.Value part = parts.index(i);
                MiniJson.Value piece = part.optionalField("text");
                if (piece != null && !piece.isNull()) {
                    text.append(piece.asString());
                }
            }
            String briefing = text.toString().trim();
            if (briefing.isEmpty()) {
                throw new AdvisorUnavailableException(AdvisorUnavailableException.BAD_RESPONSE,
                        "The AI service returned an empty briefing.",
                        "Try again; the dashboard's own summary is shown instead.");
            }
            return cap(briefing);
        } catch (MiniJson.Malformed e) {
            throw new AdvisorUnavailableException(AdvisorUnavailableException.BAD_RESPONSE,
                    "The AI service's reply did not have the expected shape ("
                            + e.getMessage() + ").",
                    "This is usually temporary. Try again, or check that "
                            + AdvisorConfig.FILE_NAME + " names a model that exists.");
        }
    }

    /**
     * Keeps the briefing to a readable length, cutting at a word boundary.
     *
     * <p>A model can return far more than the page can show; truncating here means the
     * page never has to decide, and the cut is marked so nobody thinks the model stopped
     * mid-sentence of its own accord.</p>
     */
    static String cap(String briefing) {
        if (briefing.length() <= MAX_BRIEFING_CHARS) {
            return briefing;
        }
        int cut = briefing.lastIndexOf(' ', MAX_BRIEFING_CHARS);
        if (cut < MAX_BRIEFING_CHARS / 2) {
            cut = MAX_BRIEFING_CHARS;
        }
        return briefing.substring(0, cut).trim() + " [...]";
    }

    /**
     * Turns a transport failure into the right reason.
     *
     * <p>The rule is evidence, not guesswork. A reason is chosen only for what the response
     * actually states:</p>
     * <ol>
     *   <li>{@code 429} - rate limiting, which says so in the status alone;</li>
     *   <li>a credential reason, or {@code 401}/{@code 403} - the key was refused;</li>
     *   <li>clear model evidence - {@link ServiceHttpException#isModelProblem()}, which
     *       requires the response to name a model;</li>
     *   <li>anything else, including a bare {@code 400} - {@code RequestRejected}, because
     *       the service refused the request without saying which part was wrong.</li>
     * </ol>
     *
     * <p>Earlier versions mapped every remaining 400 to {@code ModelUnavailable}. That
     * asserted two things a bare 400 never says: that the key was accepted, and that the
     * model was the problem. A malformed request would have been reported as a retired
     * model, sending the user to change a setting that was fine.</p>
     */
    private static AdvisorUnavailableException fromTransport(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();

        if (e instanceof ServiceHttpException http) {
            int status = http.getStatusCode();
            if (status == 429) {
                return rateLimited(message, e);
            }
            if (http.isCredentialProblem() || status == 401 || status == 403) {
                return keyRejected(message, e);
            }
            if (http.isModelProblem()) {
                return modelUnavailable(message, e);
            }
            if (status >= 500) {
                return unreachable(message, e);
            }
            return requestRejected(message, e);
        }

        // A plain IOException - no status code. This is a fake model in a check, a proxy, or
        // a transport failure, so the text is all there is. Model wording is tested before
        // key wording, because a model sentence may mention the API key.
        String lower = message.toLowerCase();
        boolean namesModel = lower.contains("model");
        boolean saysGone = lower.contains("not found") || lower.contains("shut down")
                || lower.contains("no longer available") || lower.contains("not supported")
                || lower.contains("unknown model") || lower.contains("unsupported model");
        if (namesModel && saysGone) {
            return modelUnavailable(message, e);
        }
        if (lower.contains("answered 401") || lower.contains("answered 403")
                || lower.contains("api_key_invalid")) {
            return keyRejected(message, e);
        }
        if (lower.contains("answered 429")) {
            return rateLimited(message, e);
        }
        if (lower.contains("answered 400") || lower.contains("answered 404")
                || lower.contains("answered 4")) {
            return requestRejected(message, e);
        }
        return unreachable(message, e);
    }

    private static AdvisorUnavailableException keyRejected(String message, IOException e) {
        return new AdvisorUnavailableException(AdvisorUnavailableException.KEY_REJECTED,
                "The AI service rejected the configured key (" + message + ").",
                "Check the key in " + AdvisorConfig.FILE_NAME + " or "
                        + AdvisorConfig.ENV_KEY + ", then restart.", e);
    }

    /**
     * The model is the stated problem.
     *
     * <p>The message says only that the model is unavailable. It deliberately does not add
     * "the key was accepted": a model error does not prove the key is valid for every other
     * model, and stating it invites the user to stop checking a key that may still be
     * wrong.</p>
     */
    private static AdvisorUnavailableException modelUnavailable(String message, IOException e) {
        return new AdvisorUnavailableException(AdvisorUnavailableException.MODEL_UNAVAILABLE,
                "The AI service says this model is not available (" + message + ").",
                "Set gemini.model in " + AdvisorConfig.FILE_NAME + " to a model that exists,"
                        + " such as " + AdvisorConfig.DEFAULT_MODEL
                        + ", then restart. The dashboard's own summary is shown instead.", e);
    }

    /**
     * The service refused the request without saying which part was wrong.
     *
     * <p>The message states what is true - the request was not accepted, and the response
     * did not identify the cause - and offers both plausible places to look. It must not
     * claim the key was accepted, and must not direct the user to one specific setting on
     * no evidence.</p>
     */
    private static AdvisorUnavailableException requestRejected(String message, IOException e) {
        return new AdvisorUnavailableException(AdvisorUnavailableException.REQUEST_REJECTED,
                "The AI service did not accept this request, and did not say which part was"
                        + " wrong (" + message + ").",
                "Check the gemini.model and gemini.apiKey settings in "
                        + AdvisorConfig.FILE_NAME + " (or " + AdvisorConfig.ENV_KEY
                        + "), then try again. The dashboard's own summary is shown instead.",
                e);
    }

    private static AdvisorUnavailableException rateLimited(String message, IOException e) {
        return new AdvisorUnavailableException(AdvisorUnavailableException.UNREACHABLE,
                "The AI service is rate-limiting this key (" + message + ").",
                "Wait a minute and try again; the dashboard's own summary is shown"
                        + " instead.", e);
    }

    private static AdvisorUnavailableException unreachable(String message, IOException e) {
        return new AdvisorUnavailableException(AdvisorUnavailableException.UNREACHABLE,
                "The AI service could not be reached (" + message + ").",
                "Check this machine's internet connection, then try again. The dashboard's"
                        + " own summary is shown instead.", e);
    }
}
