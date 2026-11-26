package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

/**
 * Creates the HTTP server, attaches the two contexts, and reports which port it got.
 *
 * <p>The server is started on port 8080 as agreed, stepping forward if that port is
 * already in use rather than failing: on a shared laptop something else holding 8080 is
 * normal, and refusing to start would look like the project is broken.</p>
 *
 * <p>{@link #start(int)} is separate from {@link #stop()} so the check program can start
 * a server on a scrap port, run requests against it, and shut it down cleanly.</p>
 */
public final class WebServer {

    private final HttpServer server;
    private final int port;
    private final ExecutorService executor;

    private WebServer(HttpServer server, int port, ExecutorService executor) {
        this.server = server;
        this.port = port;
        this.executor = executor;
    }

    /**
     * Binds and starts serving.
     *
     * @param requestedPort the first port to try; 0 asks the operating system for any
     *                      free port, which is what the check program uses
     * @param api           the API handler
     * @throws IOException when no port in the range can be bound
     */
    public static WebServer start(int requestedPort, ApiHandler api) throws IOException {
        HttpServer server = null;
        int boundPort = -1;
        IOException lastFailure = null;

        int attempts = (requestedPort == 0) ? 1 : ServerConfig.PORT_ATTEMPTS;
        for (int i = 0; i < attempts && server == null; i++) {
            int candidate = (requestedPort == 0) ? 0 : requestedPort + i;
            try {
                server = HttpServer.create(
                        new InetSocketAddress(ServerConfig.HOST, candidate), 0);
                boundPort = server.getAddress().getPort();
            } catch (IOException e) {
                lastFailure = e;
                server = null;
            }
        }
        if (server == null) {
            throw new IOException("No free port between " + requestedPort + " and "
                    + (requestedPort + attempts - 1) + " on " + ServerConfig.HOST + ".",
                    lastFailure);
        }

        // Fixed thread pool: a handful of concurrent requests is all a demo needs, and a
        // bounded pool keeps a runaway client from spawning threads without limit.
        ExecutorService executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);

        server.createContext("/api", api);
        server.createContext("/", new StaticHandler(ServerConfig.WEB_ROOT));
        server.start();

        return new WebServer(server, boundPort, executor);
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return ServerConfig.baseUrl(port);
    }

    /** Stops serving and releases the port and the threads. Safe to call twice. */
    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }
}
