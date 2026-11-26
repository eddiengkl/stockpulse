package exception;

/**
 * Thrown when the demo role is not allowed to perform an action, for example Staff
 * trying to change settings.
 *
 * <p>This is <em>demo role gating</em>, not authentication: the role is chosen from a
 * picker on the page and there is no password. It exists to show the business rule
 * being enforced in Java instead of only hiding a button in the browser.</p>
 */
public class RoleNotPermittedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String role;
    private final String action;

    public RoleNotPermittedException(String role, String action) {
        super("The " + role + " role is not allowed to " + action
                + ". Switch to Manager to do this.");
        this.role = role;
        this.action = action;
    }

    public String getRole() {
        return role;
    }

    public String getAction() {
        return action;
    }
}
