package ai;

import java.io.IOException;

/**
 * The one thing the advisor needs from the outside world: send some JSON, get some JSON.
 *
 * <p>It exists as an interface so the advisor's failure handling can be exercised for
 * real. A check cannot make Google return a malformed body on demand, and a demo laptop
 * may have no network at all - but the behaviour that matters most here is what the
 * program does when that call goes wrong. A seam this narrow keeps the untested part down
 * to one method with no logic in it.</p>
 */
@FunctionalInterface
public interface LanguageModel {

    /**
     * Sends a generation request.
     *
     * @param requestJson the request body, already built by the advisor
     * @return the response body
     * @throws IOException when the service cannot be reached, times out, or answers with
     *                     an error status; the message says which, for the user
     */
    String generate(String requestJson) throws IOException;
}
