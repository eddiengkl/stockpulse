package model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One audit line: who did what, when. Every stock change appends a line, which is what
 * lets the demo show that the Java backend, not the browser, did the work.
 */
public class ActivityLog {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter SHORT_STAMP =
            DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private LocalDateTime timestamp;
    private String role;     // "Manager" or "Staff"
    private String action;   // "SALE", "DELIVERY", "WASTE", "SETTINGS", "ROLE"
    private String detail;   // "Sold 6 L of Fresh Milk 1L from B0039"

    public ActivityLog(LocalDateTime timestamp, String role, String action, String detail) {
        if (timestamp == null) {
            throw new IllegalArgumentException("Activity timestamp is required.");
        }
        this.timestamp = timestamp;
        this.role = DataText.safeField(role, "activity role");
        this.action = DataText.safeField(action, "activity action");
        this.detail = DataText.safeField(detail, "activity detail");
    }

    public String toDataLine() {
        return STAMP.format(timestamp) + "|" + role + "|" + action + "|" + detail;
    }

    public String formattedTime() {
        return SHORT_STAMP.format(timestamp);
    }

    public LocalDateTime getTimestamp() { return timestamp; }
    public String getRole() { return role; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
}
