package server;

import java.nio.file.Path;

/**
 * Fixed settings for the web server: where it listens, and which folder it serves.
 *
 * <p>As agreed in the design, the site runs on localhost only. There is no
 * authentication, so binding to every network interface would expose the café's data to
 * the local network - the demo role picker is not a security control.</p>
 */
public final class ServerConfig {

    /** Loopback only. Do not change to 0.0.0.0: there is no authentication. */
    public static final String HOST = "127.0.0.1";

    /** First port tried. If it is busy the server steps forward through PORT_ATTEMPTS. */
    public static final int DEFAULT_PORT = 8080;

    /** How many ports to try before giving up: 8080, 8081, ... 8089. */
    public static final int PORT_ATTEMPTS = 10;

    /** Folder holding the frontend. Created in Phase 4; not created here. */
    public static final Path WEB_ROOT = Path.of("web");

    /** Largest request body accepted, in bytes. Guards against a runaway POST. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /** Name of the placeholder page served when web/ is missing. */
    public static final String PLACEHOLDER_FILE = "index.html";

    private ServerConfig() {
    }

    /** The address a browser should open for a given port. */
    public static String baseUrl(int port) {
        return "http://" + HOST + ":" + port;
    }
}
