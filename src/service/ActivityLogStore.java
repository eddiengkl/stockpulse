package service;

import java.time.LocalDateTime;

import model.ActivityLog;

/**
 * The activity trail: a plain array of {@link ActivityLog} plus a counter, newest last.
 *
 * <p>Every stock change writes one line here. The dashboard shows the last few lines so
 * a tester can see that the Java backend, not the browser, performed the change.</p>
 */
public class ActivityLogStore {

    public static final int DEFAULT_CAPACITY = 500;

    private final ActivityLog[] entries;
    private int count;

    public ActivityLogStore() {
        this(DEFAULT_CAPACITY);
    }

    public ActivityLogStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Activity log capacity must be at least 1.");
        }
        this.entries = new ActivityLog[capacity];
        this.count = 0;
    }

    /**
     * Appends a line, stamping it with the current time.
     *
     * <p>A full log is handled by dropping the oldest entry rather than refusing the
     * action: the stock change already happened, and an audit trail that blocks work
     * would be worse than one that rolls over.</p>
     */
    public void add(String role, String action, String detail) {
        ActivityLog entry = new ActivityLog(LocalDateTime.now(), role, action, detail);
        if (count >= entries.length) {
            // shift left by one, dropping the oldest
            for (int i = 1; i < count; i++) {
                entries[i - 1] = entries[i];
            }
            entries[count - 1] = entry;
            return;
        }
        entries[count] = entry;
        count++;
    }

    /** Adds a pre-built entry (used when reloading from activity.txt). */
    public void add(ActivityLog entry) {
        if (count >= entries.length) {
            for (int i = 1; i < count; i++) {
                entries[i - 1] = entries[i];
            }
            entries[count - 1] = entry;
            return;
        }
        entries[count] = entry;
        count++;
    }

    public int count() {
        return count;
    }

    public ActivityLog[] entries() {
        return entries;
    }

    /** The most recent {@code limit} entries, newest first, for the dashboard. */
    public ActivityLog[] recent(int limit) {
        int take = Math.min(limit, count);
        ActivityLog[] result = new ActivityLog[take];
        for (int i = 0; i < take; i++) {
            result[i] = entries[count - 1 - i];
        }
        return result;
    }

    public void clear() {
        for (int i = 0; i < entries.length; i++) {
            entries[i] = null;
        }
        count = 0;
    }
}
