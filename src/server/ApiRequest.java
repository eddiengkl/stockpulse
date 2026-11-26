package server;

import exception.InvalidInputException;

/**
 * One incoming API request, already parsed.
 *
 * <p>Holds three separate things: the path parameters from the route template (such as
 * an item id), the query string, and the form-urlencoded body. They are kept apart
 * because a route must not read {@code qty} from the query string by accident when the
 * body is what was meant.</p>
 *
 * <p>The demo role is read from the {@code X-Demo-Role} header, falling back to a
 * {@code role} query parameter so a request can be reproduced by pasting a URL into a
 * browser. That role is <em>not</em> authentication; it is the picker on the page, and it
 * is enforced in Java by {@link service.InventoryService}.</p>
 */
public final class ApiRequest {

    /** Header carrying the demo role chosen on the page. */
    public static final String ROLE_HEADER = "X-Demo-Role";

    private final String method;
    private final String path;
    private final Route.Match match;
    private final RequestMap query;
    private final RequestMap body;
    private final String role;
    private final String clientAddress;

    public ApiRequest(String method, String path, Route.Match match, RequestMap query,
                      RequestMap body, String role, String clientAddress) {
        this.method = method;
        this.path = path;
        this.match = match;
        this.query = query;
        this.body = body;
        this.role = role;
        this.clientAddress = clientAddress;
    }

    public String method() {
        return method;
    }

    public String path() {
        return path;
    }

    public RequestMap query() {
        return query;
    }

    public RequestMap body() {
        return body;
    }

    /** The demo role: "Manager", "Staff", or whatever the caller claimed. */
    public String role() {
        return role;
    }

    public String clientAddress() {
        return clientAddress;
    }

    /**
     * A path parameter from the route template.
     *
     * @throws InvalidInputException when the route did not capture that name, which
     *                               means the handler is wired to the wrong template
     */
    public String param(String name) throws InvalidInputException {
        String value = match.param(name);
        if (value == null || value.isBlank()) {
            throw new InvalidInputException(name, "The request is missing the '" + name + "' part of the path.");
        }
        return value;
    }
}
