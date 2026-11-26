package server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import exception.AdvisorUnavailableException;
import exception.DataFileException;
import exception.ExpiredStockException;
import exception.InsufficientStockException;
import exception.InvalidInputException;
import exception.InvalidSeasonException;
import exception.ItemNotFoundException;
import exception.RoleNotPermittedException;
import exception.StorageFullException;

/**
 * The API endpoint. One method of dispatch, one place that writes a response, and one
 * place that turns an exception into a status code.
 *
 * <p>Every response is either {@code {"ok":true,"data":...}} or
 * {@code {"ok":false,"error":{"code":...,"message":...,"detail":...}}}. The
 * {@code code} is the exception's class name, so the page can react to a refusal without
 * matching on the wording of a message that may be reworded later.</p>
 *
 * <p>No business rule lives here. This class resolves the route, reads the fields,
 * calls one service method, and formats the answer. Decisions such as "is this item at
 * risk" and "how much should be reordered" belong to the services.</p>
 */
public final class ApiHandler implements HttpHandler {

    private static final String JSON_TYPE = "application/json; charset=utf-8";

    private final Routes routes;

    public ApiHandler(Routes routes) {
        this.routes = routes;
    }

    /** A failed save, so the response can say plainly what happened to the change. */
    private static final String STORAGE_BLOCKED_CODE = "StorageBlockedException";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            String body = readBody(exchange);
            Route.Match match = routes.match(method, path);
            if (match == null) {
                // Distinguish "wrong method" from "no such path", because the two are
                // very different mistakes to debug from a browser console.
                String allowed = routes.allowedMethods(path);
                if (allowed != null) {
                    send(exchange, 405, error("MethodNotAllowed",
                            method + " is not allowed on " + path + ". Use " + allowed + ".",
                            null));
                    return;
                }
                send(exchange, 404, error("RouteNotFound",
                        "No API route matches " + method + " " + path + ".", null));
                return;
            }

            ApiRequest request = buildRequest(exchange, match, body);
            Route route = routes.routeFor(method, path);
            String data = route.handler().handle(request);
            send(exchange, 200, ok(data));
        } catch (Exception e) {
            // Every failure in the system passes through here, which is what makes the
            // error contract in the README true rather than aspirational.
            handleFailure(exchange, e);
        }
    }

    // --------------------------------------------------------------- request

    private ApiRequest buildRequest(HttpExchange exchange, Route.Match match, String body)
            throws InvalidInputException {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String role = exchange.getRequestHeaders().getFirst(ApiRequest.ROLE_HEADER);
        RequestMap query = RequestMap.parse(rawQuery);
        if (role == null || role.isBlank()) {
            role = query.get("role");
        }
        RequestMap fields = body.isBlank() ? RequestMap.empty() : RequestMap.parse(body);
        String address = exchange.getRemoteAddress() == null
                ? "unknown" : exchange.getRemoteAddress().getAddress().getHostAddress();
        return new ApiRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                match, query, fields, (role == null) ? "" : role.trim(), address);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        byte[] buffer = new byte[4096];
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = in.read(buffer)) != -1) {
            collected.write(buffer, 0, read);
            if (collected.size() > ServerConfig.MAX_BODY_BYTES) {
                // Stop reading rather than buffering an unbounded body.
                break;
            }
        }
        return collected.toString(StandardCharsets.UTF_8.name());
    }

    // -------------------------------------------------------------- failure

    /**
     * Maps each custom exception to the status code agreed in the design.
     *
     * <p>The order of the {@code catch} blocks matters only where one exception could be
     * a subclass of another; these are all siblings, so the order is for readability.</p>
     */
    private void handleFailure(HttpExchange exchange, Exception e) throws IOException {
        if (e instanceof ItemNotFoundException item) {
            send(exchange, 404, error("ItemNotFoundException", item.getMessage(),
                    "lookedForId=" + item.getLookedForId()));
        } else if (e instanceof InvalidInputException input) {
            send(exchange, 400, error("InvalidInputException", input.getMessage(),
                    "field=" + input.getField()));
        } else if (e instanceof InvalidSeasonException season) {
            send(exchange, 400, error("InvalidSeasonException", season.getMessage(), null));
        } else if (e instanceof RoleNotPermittedException role) {
            send(exchange, 403, error("RoleNotPermittedException", role.getMessage(),
                    "role=" + role.getRole() + "; action=" + role.getAction()));
        } else if (e instanceof InsufficientStockException stock) {
            send(exchange, 409, error("InsufficientStockException", stock.getMessage(),
                    "requested=" + Json.numbers(stock.getRequested())
                            + "; available=" + Json.numbers(stock.getAvailable())));
        } else if (e instanceof ExpiredStockException expired) {
            send(exchange, 409, error("ExpiredStockException", expired.getMessage(),
                    "batchId=" + expired.getBatchId()
                            + "; daysExpired=" + expired.getDaysExpired()));
        } else if (e instanceof StorageFullException full) {
            send(exchange, 500, error("StorageFullException", full.getMessage(),
                    "store=" + full.getStoreName() + "; capacity=" + full.getCapacity()));
        } else if (e instanceof DataFileException file) {
            // A save failed after the change had been applied in memory. Routes has
            // already put memory back, but only when no data file holds the new records.
            // Three facts travel with the exception and must not be merged into one:
            // whether the new records reached the disk (committed), whether the files are
            // one moment or a mixture (datasetConsistent), and whether a normal start would
            // load the folder (recoveredOnStartup). They are not the same question - a
            // committed save with a leftover marker is consistent yet unloadable - and a
            // caller that derived one from another would promise a restart the program
            // would then reject.
            WriteGuard guard = routes.guard();
            send(exchange, 503, error(STORAGE_BLOCKED_CODE, saveFailureMessage(file),
                    "file=" + file.getFileName() + "; line=" + file.getLineNumber()
                            + "; committed=" + guard.wasDataCommitted()
                            + "; rolledBack=" + guard.wasMemoryRolledBack()
                            + "; datasetConsistent=" + guard.isDatasetConsistent()
                            + "; restartLoadsSavedData=" + guard.isRecoveredOnStartup()
                            + "; writesBlocked=" + guard.isBlocked()));
        } else if (e instanceof AdvisorUnavailableException advisor) {
            // Payloads catches this itself and answers 200 with a labelled rule-based
            // fallback, so this branch is a safety net: if a future handler ever lets the
            // exception escape, the client still gets the machine-readable reason rather
            // than a generic 500. The reason is the field the page switches on.
            send(exchange, 503, error("AdvisorUnavailableException", advisor.getMessage(),
                    "reason=" + advisor.getReason()
                            + "; remediation=" + advisor.getRemediation()));
        } else {
            send(exchange, 500, error("InternalError",
                    "The server hit an unexpected problem: " + e.getClass().getSimpleName(),
                    safeDetail(e)));
        }
    }

    private static String safeDetail(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? null : message;
    }

    /**
     * Drops a trailing full stop so a recorded reason can be spliced into the middle of a
     * sentence without printing "unreliable - the reason..".
     */
    private static String trimFullStop(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * The wording for a failed save, stating only what is actually true.
     *
     * <p>There are four outcomes, and confusing any two of them would mislead someone
     * about their own data:</p>
     * <ul>
     *   <li><strong>Committed, cleanup unfinished.</strong> All seven files hold the new
     *       records, but a backup or the marker could not be deleted. The change
     *       <em>was</em> saved, so memory was deliberately left holding it. Saying "not
     *       saved" here would be false, and advising a plain restart would be wrong too:
     *       start-up refuses the folder until the leftover is removed. The advice is to
     *       delete the named file and start again - not to retry the change, which would
     *       overwrite the very copy of the old records that is still on disk.</li>
     *   <li><strong>Committed, and nothing left behind.</strong> The save finished; there is
     *       nothing to say, and this branch exists only so the code cannot pretend an
     *       unfinished cleanup is one.</li>
     *   <li><strong>Reversed cleanly.</strong> No data file holds the new records, memory was
     *       rolled back, and the marker is gone - so a restart after the folder is writable
     *       again actually works. {@code ApiCheck} proves that by loading the folder with a
     *       fresh {@code Wire}.</li>
     *   <li><strong>Reversed on disk, not in memory.</strong> No data file holds the new
     *       records and the folder is whole, but putting the earlier figures back in memory
     *       failed ({@link WriteGuard#isMemoryUnreliable()}). The files are the copy to
     *       trust; the figures on screen are the part that is wrong. This is decided by the
     *       memory fact and not by consistency, because the disk is consistent here - the
     *       response used to reach the "undone in memory and on disk" sentence in this case
     *       and contradict its own {@code rolledBack=false}.</li>
     *   <li><strong>Reversed and reversing failed too.</strong> The files may mix old and
     *       new records, and the marker and backups were deliberately kept. Saying memory
     *       matches the files would be false, and advising a plain restart would risk
     *       loading that mixture - so the message says recovery is needed by hand and
     *       repeats the hint naming the files to compare.</li>
     * </ul>
     *
     * <p>The figures in memory are worth a sentence of their own in each branch, and only
     * what the guard observed: whether the rollback happened, or whether it failed and why
     * ({@link WriteGuard#memoryProblem()}).</p>
     */
    private String saveFailureMessage(DataFileException file) {
        WriteGuard guard = routes.guard();
        if (guard.wasDataCommitted()) {
            // The change is on disk. Two sub-cases: nothing left to do (should not be
            // reachable, but saying nothing is better than saying something false), or a
            // leftover that a person has to delete.
            if (guard.isRecoveredOnStartup()) {
                return "The change was saved: all seven data files hold the new records,"
                        + " and the folder is ready to be loaded.";
            }
            return "The change was saved: all seven data files hold the new records, so the"
                    + " figures in memory are the saved ones."
                    + " Removing the files that were only there to support the save did not"
                    + " finish (" + file.getMessage() + "), so a normal start would refuse"
                    + " the folder even though the records in it are whole."
                    + " " + guard.recoveryHint()
                    + " Do not repeat the change meanwhile: it is already saved, and a"
                    + " second save would overwrite the copy of the earlier records that is"
                    + " still on disk.";
        }
        String opening = "The change was not saved: " + file.getMessage() + ".";
        // Not committed, and the disk is not whole - the files may mix old and new records,
        // and a start may or may not load them. Memory is a separate question that has to be
        // answered on its own: a restore failure must not borrow the disk's facts, and the
        // disk's facts must not be softened because the figures on screen are wrong too.
        if (!guard.isDatasetConsistent()) {
            String hint = guard.recoveryHint();
            String memory = guard.wasMemoryRolledBack()
                    ? " The figures in memory were put back, so the screen still matches"
                            + " the last state that was saved."
                    : " Putting the earlier figures back in memory failed as well, so the"
                            + " figures shown are not the saved ones either: "
                            + trimFullStop(guard.memoryProblem()) + ".";
            // The closing advice has to agree with what startup actually does. It used to
            // say a restart "would load whichever mixture is on disk" and then say startup
            // refuses the folder - two claims that cannot both be true. A restart here loads
            // nothing at all while the marker or a backup is still there, so the sentence is
            // split: what to expect from a restart, then what startup does until the files
            // are recovered.
            return opening + " Reversing it also failed, so the data files on disk may"
                    + " hold a mixture of old and new records - they may not match the"
                    + " figures waiting in memory." + memory
                    + (hint.isEmpty()
                            ? ""
                            : " What a person has to do about it: " + trimFullStop(hint) + ".")
                    + " Do not restart expecting the saved data."
                    + (guard.isRecoveredOnStartup()
                            ? ""
                            : " Startup will refuse to load this folder until the files are"
                                    + " recovered.")
                    + " Further changes are refused until the server is stopped.";
        }
        // Nothing was committed, and the files were not left in a mixture. Two outcomes fit
        // here and they are not the same sentence: memory was put back (the usual case), or
        // memory could not be put back. The second one is decided by the memory fact, never
        // by consistency - the disk is consistent in both when this branch is reached.
        if (guard.isMemoryUnreliable()) {
            String restart = guard.isRecoveredOnStartup()
                    ? " The data files still hold the last saved data, and starting the"
                            + " program again would load them as usual."
                    : " " + guard.recoveryHint();
            return opening + " None of it reached the data files: they still hold the last"
                    + " saved data." + restart
                    + " The figures shown in memory, however, may now be unreliable -"
                    + " " + trimFullStop(guard.memoryProblem())
                    + " Stop the server rather than trusting or repeating the change; the"
                    + " saved files are the copy that is still correct."
                    + " Further changes are refused until the server is stopped.";
        }
        // Clean reversal: every file that had been replaced is back, the backups are gone
        // and so is the marker, so the folder is exactly what the last good save left and
        // a normal start loads it. Saying so is only safe because all of those are true -
        // which is why the restart sentence follows the recovery flag, not consistency.
        String restart = guard.isRecoveredOnStartup()
                ? " Nothing needs repairing in the data folder: once the folder is writable"
                        + " again, stop the server and start it normally, and it will load"
                        + " the saved data as usual."
                : " " + guard.recoveryHint();
        if (guard.isBlocked()) {
            return opening + " It has been undone in memory and on disk, so the figures"
                    + " still match the files." + restart;
        }
        return opening + " It has been undone in memory and on disk, so the figures still"
                + " match the files.";
    }

    // ------------------------------------------------------------- responses

    private static String ok(String data) {
        return "{\"ok\":true,\"data\":" + data + "}";
    }

    private static String error(String code, String message, String detail) {
        Json json = new Json();
        json.beginObject();
        json.put("ok", false);
        json.comma();
        json.name("error").beginObject();
        json.put("code", code);
        json.comma();
        json.put("message", message == null ? "" : message);
        json.comma();
        json.put("detail", detail);
        json.endObject();
        json.endObject();
        return json.toString();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
