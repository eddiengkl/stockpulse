package server;

/**
 * One route: an HTTP method, a path template such as {@code /api/items/{id}/sale}, and
 * the handler that serves it.
 *
 * <p>Templates exist because several routes share a shape. Matching them here keeps the
 * dispatcher a short loop instead of a long chain of {@code if (path.startsWith(...))}
 * checks that inevitably disagree with each other about trailing slashes.</p>
 */
public final class Route {

    /** What a matched route hands back to its handler. */
    @FunctionalInterface
    public interface Handler {
        /**
         * @param request the matched request, including any path parameters
         * @return the response body text
         * @throws Exception so that every custom exception can reach the dispatcher,
         *                   which is the single place that turns exceptions into status codes
         */
        String handle(ApiRequest request) throws Exception;
    }

    private final String method;
    private final String[] segments;
    private final Handler handler;

    /**
     * @param method   GET, POST or PUT
     * @param template a path such as {@code /api/items/{id}}
     */
    public Route(String method, String template, Handler handler) {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("A route needs an HTTP method.");
        }
        if (handler == null) {
            throw new IllegalArgumentException("A route needs a handler.");
        }
        this.method = method.trim();
        this.segments = split(template);
        this.handler = handler;
    }

    public String method() {
        return method;
    }

    public Handler handler() {
        return handler;
    }

    /** The template as written, for error messages and the route listing. */
    public String template() {
        return "/" + String.join("/", segments);
    }

    /**
     * Matches a decoded request path against this route.
     *
     * @return a {@link Match} carrying the path parameters, or null when this route does
     *         not handle the path
     */
    public Match match(String method, String path) {
        if (!equalsIgnoreCase(this.method, method)) {
            return null;
        }
        String[] parts = split(path);
        if (parts.length != segments.length) {
            return null;
        }
        String[] names = new String[segments.length];
        String[] values = new String[segments.length];
        int params = 0;
        for (int i = 0; i < segments.length; i++) {
            String template = segments[i];
            if (template.length() > 1 && template.startsWith("{") && template.endsWith("}")) {
                if (parts[i].isEmpty()) {
                    return null;   // /api/items//sale is not /api/items/{id}/sale
                }
                names[params] = template.substring(1, template.length() - 1);
                values[params] = parts[i];
                params++;
            } else if (!template.equalsIgnoreCase(parts[i])) {
                return null;
            }
        }
        String[] finalNames = new String[params];
        String[] finalValues = new String[params];
        for (int i = 0; i < params; i++) {
            finalNames[i] = names[i];
            finalValues[i] = values[i];
        }
        return new Match(finalNames, finalValues);
    }

    /**
     * Local method, so an HTTP method comparison does not depend on the machine locale
     * (the Turkish locale, for instance, maps 'i' to a dotted capital I).
     */
    private static boolean equalsIgnoreCase(String a, String b) {
        return (a == null) ? b == null : a.equalsIgnoreCase(b);
    }

    /**
     * Splits on '/' and drops a single trailing slash, so {@code /api/items/} and
     * {@code /api/items} are the same path. An empty path becomes zero segments.
     */
    private static String[] split(String path) {
        String trimmed = (path == null) ? "" : path.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("/", -1);
    }

    /** A successful match: the path parameters, ready to look up by name. */
    public static final class Match {
        private final String[] names;
        private final String[] values;

        Match(String[] names, String[] values) {
            this.names = names;
            this.values = values;
        }

        public String param(String name) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) {
                    return values[i];
                }
            }
            return null;
        }

        public int count() {
            return names.length;
        }
    }
}
