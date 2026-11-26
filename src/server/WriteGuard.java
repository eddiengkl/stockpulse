package server;

import exception.DataFileException;

/**
 * Stops the server accepting changes after a save has failed.
 *
 * <p>A mutation changes memory first and writes to disk second. If the write fails the
 * running server holds data the files do not have, and every later change would build on
 * that divergence - so a restart would silently lose whatever happened after the first
 * failure. Two choices were possible: undo the change, or stop writing. This class does
 * <strong>both</strong>, in that order:</p>
 *
 * <ol>
 *   <li>the caller restores memory from a snapshot of what the files still hold;</li>
 *   <li>whatever the outcome, the server refuses further writes with a 503, so no second
 *       unsaved change can be stacked on top of the first.</li>
 * </ol>
 *
 * <p>Reads keep working. Showing the last state the files agreed on is more useful than a
 * blank screen, and the read responses carry {@code writesBlocked} so the page can say
 * so plainly.</p>
 *
 * <p>A clean rollback is the normal outcome, but not the only one. Reversing a partly
 * applied save can itself fail - the disk may be gone, not just full - and then the files
 * on disk are a mixture of old and new records. The guard remembers that
 * ({@link #isDatasetConsistent()}) so the response can say the data needs recovery
 * instead of pretending memory and disk still agree.</p>
 *
 * <p>What the guard records has to match what is on disk, because the message it produces
 * is advice about restarting. A clean rollback also clears the save marker, so a restart
 * really does load the folder ({@link data.DataStore}); a failed rollback keeps both the
 * marker and the backups, so a restart really does refuse it. If the two disagreed, the
 * message would tell someone to do something the program would then reject.</p>
 *
 * <p>There is a third outcome the guard must not flatten into either of those: a save that
 * <em>committed</em> and then could not remove its own marker or a backup. The new records
 * are on disk and consistent, yet a start refuses the folder. In that case memory is
 * deliberately left holding the change - rolling it back would make the screen disagree
 * with the files - so {@link #wasMemoryRolledBack()} answers false, and the restart
 * question is answered by {@link #isRecoveredOnStartup()} rather than derived from
 * consistency.</p>
 *
 * <p>The facts are kept apart on purpose. Rolling memory back and putting the files
 * back are different jobs, done by different code, and one can succeed while the other
 * fails: memory is restored by copying Java objects, which cannot fail the way a disk can.
 * A single flag for both would therefore have to lie in exactly the case that matters -
 * the figures in memory are trustworthy but the files are not -
 * so {@link #wasMemoryRolledBack()} records the memory side separately.</p>
 */
public final class WriteGuard {

    /** The save method, narrowed so this class cannot load or delete anything. */
    @FunctionalInterface
    public interface DataStoreLike {
        /**
         * Saves everything, or reports why not.
         *
         * @return the cleanup that still needs doing after a save that committed, or
         *         {@code null} when nothing is left to do
         */
        data.DataStore.CleanupIncomplete saveAll() throws DataFileException;
    }

    private final DataStoreLike store;
    private String blockedFile;
    private String blockedReason;
    private boolean dataCommitted;
    private boolean datasetConsistent = true;
    private boolean recoveredOnStartup = true;
    private boolean memoryRolledBack = true;
    private String memoryProblem = "";
    private String recoveryHint = "";

    /**
     * The failure as it was recorded, kept so a retry can be answered with exactly the same
     * facts instead of a rebuilt approximation.
     *
     * <p>Rebuilding it from the fields used to lose the committed flag - a committed save
     * whose cleanup failed would come back as "not committed" on the next request - which
     * is precisely the kind of quiet contradiction this class exists to prevent.</p>
     */
    private DataFileException recorded;

    public WriteGuard(DataStoreLike store) {
        this.store = store;
    }

    /** True when a save has failed and writes are now refused. */
    public boolean isBlocked() {
        return blockedReason != null;
    }

    /** The file named at the last failure, or "" when writing is fine. */
    public String blockedFile() {
        return blockedFile == null ? "" : blockedFile;
    }

    /** The reason stored at the last failure, or null when writing is fine. */
    public String blockedReason() {
        return blockedReason;
    }

    /**
     * True when the last save did place the new records on disk, so memory must keep them.
     */
    public boolean wasDataCommitted() {
        return dataCommitted;
    }

    /**
     * False when the last failed save could not be reversed on disk, so the data files
     * may be a mixture of old and new records. A committed save is always consistent.
     */
    public boolean isDatasetConsistent() {
        return datasetConsistent;
    }

    /**
     * True when starting the program again would load the folder as it stands.
     *
     * <p>Separate from {@link #isDatasetConsistent()} because a committed save can leave a
     * marker behind: the records are whole, and the folder is still refused. Advice about
     * restarting is only worth giving if it is this flag that is true.</p>
     */
    public boolean isRecoveredOnStartup() {
        return recoveredOnStartup;
    }

    /**
     * True when memory was put back to the state the files last agreed on, which is a
     * separate fact from {@link #isDatasetConsistent()}.
     *
     * <p>It is false in two different situations, and a caller has to tell them apart by
     * {@link #wasDataCommitted()}:</p>
     * <ul>
     *   <li>the save committed, so memory was <em>deliberately</em> left holding the new
     *       records - they are the ones on disk - and rolling back would be the mistake;</li>
     *   <li>memory could not be restored at all, recorded by
     *       {@link #blockWithMemoryStillChanged}, and then {@link #memoryProblem()} says
     *       why.</li>
     * </ul>
     */
    public boolean wasMemoryRolledBack() {
        return memoryRolledBack;
    }

    /**
     * Why memory could not be trusted, when it could not; empty otherwise.
     *
     * <p>Separate from the blocked reason so that a caller can say both things without
     * having to parse one sentence for the other.</p>
     */
    public String memoryProblem() {
        return memoryProblem;
    }

    /** What to do about a folder that needs a person; empty when there is nothing to say. */
    public String recoveryHint() {
        return recoveryHint;
    }

    /**
     * Throws when writes are refused, so a route can stop before touching memory.
     *
     * <p>The refusal carries every fact recorded at the original failure, not just the
     * file and the reason. A second request arriving after an unrecoverable save would
     * otherwise be answered as though the folder were clean - because a fresh exception
     * would describe only itself - and the retry message would tell the user a restart is
     * safe when startup would in fact refuse the folder. The same applies to a save that
     * committed and left its marker: the retry must not claim memory was rolled back when
     * it deliberately was not.</p>
     *
     * @throws DataFileException carrying the original file, reason and recovery facts
     */
    public void requireWritable() throws DataFileException {
        if (blockedReason != null) {
            if (recorded != null) {
                // Rethrowing the same object keeps every fact exactly as it was observed,
                // including committed, which a rebuilt exception would have to guess.
                throw recorded;
            }
            throw new DataFileException(blockedFile, 0, blockedReason, null,
                    dataCommitted, datasetConsistent, recoveredOnStartup, recoveryHint);
        }
    }

    /**
     * Saves the whole dataset, and blocks further writes if the save fails.
     *
     * <p>Called only after a mutation has already been applied in memory. The block is
     * set before anything propagates, so even a caller that mishandles the exception
     * cannot leave the server accepting more writes.</p>
     *
     * <p>A save that committed and then could not tidy up returns rather than throws: the
     * new records are on disk, so the caller must keep the change in memory. The guard is
     * still blocked - a second save would overwrite the recovery copy - and the facts it
     * records say that memory matches the files and that a start needs one file removed
     * first.</p>
     *
     * @throws DataFileException when no data file holds the new records; the guard is
     *                           blocked and tells the caller whether memory was put back
     */
    public void saveOrBlock() throws DataFileException {
        data.DataStore.CleanupIncomplete unfinished;
        try {
            unfinished = store.saveAll();
        } catch (DataFileException e) {
            blockOnFailure(e);
            throw e;
        }
        if (unfinished != null) {
            blockOnUnfinishedCleanup(unfinished);
        }
    }

    private void blockOnFailure(DataFileException e) {
        this.blockedFile = e.getFileName();
        this.blockedReason = e.getMessage();
        this.dataCommitted = e.isCommitted();
        this.datasetConsistent = e.isDatasetConsistent();
        this.recoveredOnStartup = e.isRecoveredOnStartup();
        this.recoveryHint = e.getRecoveryHint();
        this.recorded = e;
    }

    /**
     * Records a save that committed but left recovery files behind.
     *
     * <p>Memory is left holding the change on purpose: the files hold those same records,
     * so putting the old ones back would be the divergence, not the cure. The reason is the
     * cleanup message itself, which already says the change was saved - so the response can
     * lead with a truthful "saved, with one step left" instead of "not saved".</p>
     */
    private void blockOnUnfinishedCleanup(data.DataStore.CleanupIncomplete unfinished) {
        DataFileException reason = unfinished.reason();
        this.blockedFile = reason.getFileName();
        this.blockedReason = reason.getMessage();
        this.dataCommitted = true;
        this.datasetConsistent = true;
        this.recoveredOnStartup = false;
        this.memoryRolledBack = false;
        this.recoveryHint = reason.getRecoveryHint();
        this.recorded = reason;
    }

    /**
     * Remembers a failure that happened before the save was even reached, such as a
     * snapshot that could not be restored. Keeps the original reason visible.
     */
    public void blockWith(String fileName, String reason) {
        this.blockedFile = fileName;
        this.blockedReason = reason;
    }

    /**
     * Remembers that memory could not be put back after a save that wrote nothing.
     *
     * <p><strong>The disk facts come from {@code saveFailure}, never from here.</strong>
     * The save reported them before the restore was even attempted: the files may be
     * whole, may be a mixture, and may or may not be loadable by a start. A restore
     * failure says nothing about any of that - memory is restored by copying Java objects
     * and never touches the disk. Writing {@code datasetConsistent=true} and
     * {@code recoveredOnStartup=true} here, as an earlier version did, would have told
     * someone whose folder was a mixture that a restart was safe.</p>
     *
     * <p>Only the memory side is decided by this method, and only in one direction: the
     * rollback did not happen, so {@link #wasMemoryRolledBack()} is false whatever the
     * exception thought. {@code committed} is kept as the save reported it - a restore
     * failure cannot turn a committed save into an uncommitted one.</p>
     *
     * @param saveFailure the error the save raised, carrying every disk fact
     * @param why         why memory could not be restored, in a sentence for the user;
     *                    appended to the save's own recovery hint rather than replacing it
     */
    public void blockWithMemoryStillChanged(DataFileException saveFailure, String why) {
        this.blockedFile = saveFailure.getFileName();
        this.blockedReason = saveFailure.getMessage();
        this.dataCommitted = saveFailure.isCommitted();
        this.datasetConsistent = saveFailure.isDatasetConsistent();
        this.recoveredOnStartup = saveFailure.isRecoveredOnStartup();
        this.recoveryHint = saveFailure.getRecoveryHint();
        this.memoryRolledBack = false;
        this.memoryProblem = why == null ? "" : why;
        this.recorded = saveFailure;
    }

    /**
     * True only for the one case where the files are fine and memory is not: a save that
     * wrote nothing could not be reversed in memory, recorded by
     * {@link #blockWithMemoryStillChanged}.
     *
     * <p>This is the fact a message must test <em>before</em> it says anything like
     * "undone in memory and on disk". Consistency alone cannot answer it - the disk is
     * consistent here - and the committed flag cannot either, because a committed save
     * leaves memory changed on purpose.</p>
     *
     * <p>"The files are fine" is checked, not assumed: a mixture on disk is reported by
     * {@link #isDatasetConsistent()} being false, and that message is about recovery, not
     * about which copy of the figures to distrust.</p>
     */
    public boolean isMemoryUnreliable() {
        return !dataCommitted && datasetConsistent && !memoryRolledBack;
    }
}
