package app;

import java.io.IOException;
import java.nio.file.Path;

import data.DataFiles;
import exception.DataFileException;
import exception.InvalidSeasonException;
import exception.StorageFullException;
import model.Settings;
import server.ApiHandler;
import server.Payloads;
import server.ServerConfig;
import server.StaticHandler;
import server.WebServer;

/**
 * Starts StockPulse: load the data files, wire the services, serve the API.
 *
 * <p>The order is deliberate. Data is loaded <em>before</em> the port is opened, so the
 * server never serves a half-loaded dataset. If loading fails, the program prints the
 * file and line to the console and exits with a non-zero status; a browser tab with a
 * spinner and no explanation is the worst possible outcome for a demo.</p>
 *
 * <p>The object graph itself is built by {@link Wire}, so this class is only about
 * start-up, reporting and shutting down.</p>
 */
public final class StockPulseApp {

    /** Exit code used when the data files cannot be loaded. */
    public static final int EXIT_DATA_ERROR = 2;

    /** Exit code used when the server cannot bind a port. */
    public static final int EXIT_SERVER_ERROR = 3;

    private StockPulseApp() {
    }

    public static void main(String[] args) {
        int requestedPort = portFrom(args);

        Wire wiring;
        try {
            wiring = Wire.buildInDefaultDirectory();
        } catch (DataFileException e) {
            reportDataFileError(e);
            System.exit(EXIT_DATA_ERROR);
            return;
        } catch (StorageFullException e) {
            System.err.println();
            System.err.println("The data files hold more records than the stores can keep:");
            System.err.println("  " + e.getMessage());
            System.err.println("Nothing was changed. Reduce the number of records in "
                    + Path.of(DataFiles.DATA_DIR).toAbsolutePath() + " and start again.");
            System.exit(EXIT_DATA_ERROR);
            return;
        } catch (InvalidSeasonException e) {
            System.err.println();
            System.err.println("The season data in " + DataFiles.SEASONS + " is not usable:");
            System.err.println("  " + e.getMessage());
            System.err.println("Nothing was changed. Fix the line and start again.");
            System.exit(EXIT_DATA_ERROR);
            return;
        }

        if (wiring.seeded) {
            System.out.println("First run: sample café data written to "
                    + wiring.dataStore.directory().toAbsolutePath() + " and reloaded from disk.");
        } else {
            System.out.println("Loaded data from " + wiring.dataStore.directory().toAbsolutePath()
                    + " (" + wiring.items.count() + " items, " + wiring.batches.count()
                    + " batches, " + wiring.sales.count() + " sales, "
                    + wiring.waste.count() + " waste records).");
        }

        WebServer server;
        try {
            server = WebServer.start(requestedPort, new ApiHandler(wiring.routes));
        } catch (IOException e) {
            System.err.println();
            System.err.println("Could not start the web server: " + e.getMessage());
            System.err.println("Another program may be holding the ports. Close it, or run:");
            System.err.println("  java -cp out app.StockPulseApp 8090");
            System.exit(EXIT_SERVER_ERROR);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        printBanner(server, wiring);
    }

    /**
     * Explains a failed load in the terms the user needs: which file, which line, what to
     * do next, and the assurance that this attempt did not overwrite the file.
     *
     * <p>One case needs different words. When the last save did not finish, there is no
     * single bad line to fix: the whole folder is a mixture of two moments, and the
     * exception carries the instructions for comparing it with the backups. Printing "fix
     * or remove the line" there would send the user looking for a line that does not
     * exist.</p>
     *
     * <p>That case also cannot promise "nothing was changed": the save that left the
     * leftovers may well have replaced some of the files. The only certain fact is that
     * <em>this</em> start wrote nothing, so that is all the message claims - and the hint it
     * prints says the same thing in its own first sentence.</p>
     */
    private static void reportDataFileError(DataFileException e) {
        Path fullPath = Path.of(DataFiles.DATA_DIR).resolve(e.getFileName()).toAbsolutePath();
        System.err.println();
        if (!e.isDatasetConsistent() && !e.getRecoveryHint().isEmpty()) {
            System.err.println("The data files may hold a mixture of records from before and");
            System.err.println("after a save that did not finish, so StockPulse loaded");
            System.err.println("nothing and did not start.");
            System.err.println();
            System.err.println("  Left behind: " + e.getFileName());
            System.err.println("  Error      : " + e.getMessage());
            System.err.println();
            System.err.println(e.getRecoveryHint());
            return;
        }        System.err.println("StockPulse could not load its data, so it did not start.");
        System.err.println();
        System.err.println("  File : " + e.getFileName());
        System.err.println("  Path : " + fullPath);
        if (e.getLineNumber() > 0) {
            System.err.println("  Line : " + e.getLineNumber());
        } else {
            System.err.println("  Line : (not a single line - the whole file is affected)");
        }
        System.err.println("  Error: " + e.getMessage());
        System.err.println();
        System.err.println("No file was changed. Open the file above, fix or remove the line,");
        System.err.println("and start again. To start over from the sample data, rename the");
        System.err.println("whole folder and run once more:");
        System.err.println("  " + Path.of(DataFiles.DATA_DIR).toAbsolutePath());
    }

    /** Prints the address, the data date, and what is being served. */
    private static void printBanner(WebServer server, Wire wiring) {
        Settings settings = wiring.settings;
        System.out.println();
        System.out.println("StockPulse is running at " + server.baseUrl());
        System.out.println("  API index      : " + server.baseUrl() + "/api");
        System.out.println("  Effective date : " + Payloads.longDate(settings.effectiveDate())
                + (settings.isDemoDate() ? "  [DEMO DATE - not saved]" : "  [system date]"));
        System.out.println("  Items loaded   : " + wiring.items.count());
        System.out.println("  Seasons        : " + wiring.seasons.count());
        // Names where the key came from, never the key. The briefing endpoint still works
        // without one: it answers with a rule-based summary labelled as such.
        System.out.println("  Advisor        : " + wiring.advisorConfig.describe());
        if (StaticHandler.webFolderExists()) {
            System.out.println("  Website        : served from " + ServerConfig.WEB_ROOT);
        } else {
            System.out.println("  Website        : " + ServerConfig.WEB_ROOT
                    + " does not exist yet (built in Phase 4).");
            System.out.println("                   The address above explains this in a browser.");
        }
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");
    }

    /**
     * The port may be given as the first argument, which makes a demo on a busy laptop one
     * command away. Anything unusable falls back to the agreed default.
     */
    private static int portFrom(String[] args) {
        if (args == null || args.length == 0) {
            return ServerConfig.DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(args[0].trim());
            return (port > 0 && port < 65536) ? port : ServerConfig.DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return ServerConfig.DEFAULT_PORT;
        }
    }
}
