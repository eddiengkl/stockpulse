package store;

import exception.InvalidSeasonException;
import model.Season;

/**
 * The season table: a plain array of {@link Season} plus a counter. Seasons are few
 * (two in the sample data), so a linear scan by name is all that is needed.
 */
public class SeasonStore {

    public static final int DEFAULT_CAPACITY = 12;

    private final Season[] seasons;
    private int count;

    public SeasonStore() {
        this(DEFAULT_CAPACITY);
    }

    public SeasonStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Season store capacity must be at least 1.");
        }
        this.seasons = new Season[capacity];
        this.count = 0;
    }

    public Season[] seasons() {
        return seasons;
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return seasons.length;
    }

    public boolean isFull() {
        return count >= seasons.length;
    }

    /** @return false when the array is full */
    public boolean add(Season season) {
        if (season == null) {
            throw new IllegalArgumentException("Cannot add a null season.");
        }
        if (isFull()) {
            return false;
        }
        seasons[count] = season;
        count++;
        return true;
    }

    public Season findByName(String name) {
        if (name == null) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            Season season = seasons[i];
            if (season != null && season.getName().equalsIgnoreCase(name.trim())) {
                return season;
            }
        }
        return null;
    }

    /**
     * Replaces the month range of an existing season.
     *
     * @throws InvalidSeasonException when the months are out of range
     */
    public void updateMonths(String name, int startMonth, int endMonth) throws InvalidSeasonException {
        Season season = findByName(name);
        if (season == null) {
            throw new IllegalArgumentException("Season " + name + " is not configured.");
        }
        season.setMonths(startMonth, endMonth);
    }

    public void clear() {
        for (int i = 0; i < seasons.length; i++) {
            seasons[i] = null;
        }
        count = 0;
    }
}
