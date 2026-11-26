package server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Serves the frontend folder.
 *
 * <p>Two rules matter here. First, a request may not escape {@link ServerConfig#WEB_ROOT}
 * by sending {@code ../} - the resolved path is checked against the root rather than the
 * raw text being filtered, which is the check that actually holds. Second, when the
 * folder does not exist yet the server answers with a plain page saying so, instead of a
 * bare 404, because during Phase 3E the folder legitimately does not exist.</p>
 *
 * <p>No frontend files are created by this class or by the server. The folder is built
 * in Phase 4; until then this only explains the situation.</p>
 */
public final class StaticHandler implements HttpHandler {

    private final Path root;

    public StaticHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendText(exchange, 405, "text/plain; charset=utf-8",
                    "Only GET is supported for files.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            path = "/" + ServerConfig.PLACEHOLDER_FILE;
        }

        Path target = root.resolve(path.substring(1)).normalize();
        if (!target.startsWith(root)) {
            // The request tried to leave the folder. Say no plainly and log nothing that
            // could be mistaken for a real file path.
            sendText(exchange, 403, "text/plain; charset=utf-8",
                    "That path is outside the web folder.");
            return;
        }

        if (!Files.isRegularFile(target)) {
            if (!Files.isDirectory(root)) {
                sendText(exchange, 503, "text/html; charset=utf-8", missingWebFolderPage(exchange));
                return;
            }
            sendText(exchange, 404, "text/plain; charset=utf-8",
                    "No such file: " + path);
            return;
        }

        byte[] bytes = Files.readAllBytes(target);
        exchange.getResponseHeaders().set("Content-Type", contentType(path));
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Explains the missing folder in the browser. This is a diagnostic page, not the
     * frontend: Phase 3E is backend only.
     */
    private static String missingWebFolderPage(HttpExchange exchange) {
        int port = exchange.getLocalAddress().getPort();
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>StockPulse - backend running</title></head><body ")
                .append("style=\"font-family:system-ui,sans-serif;max-width:44rem;margin:3rem auto;")
                .append("line-height:1.5;color:#1f2933\">")
                .append("<h1>StockPulse backend is running</h1>")
                .append("<p>The Java server started and the API is answering, but the ")
                .append("<code>").append(ServerConfig.WEB_ROOT).append("</code> folder ")
                .append("does not exist yet. The website screens are built in Phase 4.</p>")
                .append("<p>Try the API directly:</p><ul>")
                .append("<li><a href=\"").append(ServerConfig.baseUrl(port)).append("/api\">/api</a>")
                .append(" - the route list</li>")
                .append("<li><a href=\"").append(ServerConfig.baseUrl(port))
                .append("/api/bootstrap\">/api/bootstrap</a> - settings and seasons</li>")
                .append("<li><a href=\"").append(ServerConfig.baseUrl(port))
                .append("/api/dashboard\">/api/dashboard</a> - stock, risks and totals</li>")
                .append("<li><a href=\"").append(ServerConfig.baseUrl(port))
                .append("/api/items\">/api/items</a> - the item list</li>")
                .append("<li><a href=\"").append(ServerConfig.baseUrl(port))
                .append("/api/activity\">/api/activity</a> - what has been recorded</li>")
                .append("</ul></body></html>");
        return html.toString();
    }

    /** Content types for the file kinds the frontend will use. */
    private static String contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }

    private static void sendText(HttpExchange exchange, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** True when the folder exists, used by the startup banner. */
    public static boolean webFolderExists() {
        return Files.isDirectory(ServerConfig.WEB_ROOT);
    }
}
