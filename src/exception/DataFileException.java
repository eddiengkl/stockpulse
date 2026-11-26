package exception;

/**
 * Thrown when a data file cannot be read because a line is malformed.
 *
 * <p>It always carries the file name and the one-based line number, and the loader
 * stops instead of skipping the bad line: a skip would mean the program starts with a
 * partial dataset and then saves it back over the good file, which is silent data
 * loss.</p>
 *
 * <p>The same class also reports a <em>save</em> that did not finish, and there the
 * details matter more than the message. A save passes through several steps, and each can
 * fail with a different result, so the outcome is described by four separate facts rather
 * than one "failed" flag. They are kept apart because they answer four different
 * questions, and a caller that merged them would have to give a false answer to at least
 * one:</p>
 * <ul>
 *   <li>{@link #isCommitted()} - were the data files replaced with the new records?</li>
 *   <li>{@link #isDatasetConsistent()} - do the files on disk hold one moment, or a
 *       mixture of old and new records?</li>
 *   <li>{@link #isRecoveredOnStartup()} - will a normal restart load the folder, or does
 *       something left behind make it refuse?</li>
 *   <li>memory is the fourth, and it is not a fact about the files at all, so it is
 *       recorded by the caller that did or did not put memory back
 *       ({@code WriteGuard#wasMemoryRolledBack}).</li>
 * </ul>
 *
 * <p>The combinations are not academic. A save that replaced all seven files and then
 * could not delete its own marker has <em>committed</em>: new records are on disk,
 * consistent, and a restart loads them after one file is removed by hand. Reporting that
 * as "the change was not saved" would be false, and rolling memory back to the old figures
 * would leave the screen and the files describing different moments - the exact divergence
 * this program exists to prevent.</p>
 */
public class DataFileException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String fileName;
    private final int lineNumber;   // 0 when the problem is not tied to a line

    /** True when the seven data files were all replaced with the new records. */
    private final boolean committed;

    /** True when the files on disk hold one moment rather than a mixture. */
    private final boolean datasetConsistent;

    /** True when a normal start would load the folder, with no file to remove by hand. */
    private final boolean recoveredOnStartup;

    /** Where on disk the failed save left files in a state that needs looking at. */
    private final String recoveryHint;

    public DataFileException(String fileName, int lineNumber, String message) {
        this(fileName, lineNumber, message, null, false, true, true, "");
    }

    public DataFileException(String fileName, int lineNumber, String message, Throwable cause) {
        this(fileName, lineNumber, message, cause, false, true, true, "");
    }

    /**
     * A save that did not commit, and whose new records are certainly not on disk.
     *
     * @param datasetConsistent   false when reversing the failed save also failed, which
     *                            means the files may be partly new and partly old
     * @param recoveredOnStartup  false when something left in the folder makes a normal
     *                            start refuse it
     * @param recoveryHint        what a person should do about it; empty when nothing is
     *                            known
     */
    public DataFileException(String fileName, int lineNumber, String message, Throwable cause,
                             boolean datasetConsistent, boolean recoveredOnStartup,
                             String recoveryHint) {
        this(fileName, lineNumber, message, cause, false, datasetConsistent,
                recoveredOnStartup, recoveryHint);
    }

    /**
     * @param committed          true only when every data file holds the new records
     * @param datasetConsistent  false when the files may mix old and new records
     * @param recoveredOnStartup false when a leftover marker or backup would make a
     *                           normal start refuse the folder
     * @param recoveryHint       what a person should do about it; empty when nothing is
     *                           known
     */
    public DataFileException(String fileName, int lineNumber, String message, Throwable cause,
                             boolean committed, boolean datasetConsistent,
                             boolean recoveredOnStartup, String recoveryHint) {
        super(fileName + (lineNumber > 0 ? " line " + lineNumber : "") + ": " + message, cause);
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.committed = committed;
        this.datasetConsistent = datasetConsistent;
        this.recoveredOnStartup = recoveredOnStartup;
        this.recoveryHint = recoveryHint == null ? "" : recoveryHint;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * True when all seven data files were replaced with the new records, and what failed
     * was the tidying up afterwards.
     *
     * <p>This is the one case where memory must <em>not</em> be rolled back: the new
     * records are the ones on disk, so the figures in memory are right and the old ones
     * would be wrong. The change happened; the save simply could not finish filing
     * itself away.</p>
     */
    public boolean isCommitted() {
        return committed;
    }

    /**
     * True when the data files were left as a whole the program can continue from - they
     * were never touched, every file that had been replaced was put back, or all seven
     * were replaced together.
     *
     * <p>False means the opposite: the save failed <em>and</em> reversing it failed, so
     * some files hold new records and others still hold old ones. Reporting that as a
     * clean rollback would be a lie, so the flag travels with the exception and the API
     * answers with it.</p>
     */
    public boolean isDatasetConsistent() {
        return datasetConsistent;
    }

    /**
     * True when starting the program again would load this folder as it stands.
     *
     * <p>It is deliberately not the same question as {@link #isDatasetConsistent()}. A
     * committed save whose marker could not be deleted is perfectly consistent, yet a
     * normal start refuses it until the marker is removed - so a message that derived this
     * from consistency would promise a restart the program would then reject. The flag
     * comes from where the failure happened, not from a guess.</p>
     */
    public boolean isRecoveredOnStartup() {
        return recoveredOnStartup;
    }

    /**
     * What a person should do next, phrased as an instruction for someone looking at the
     * folder; empty when there is nothing to say.
     */
    public String getRecoveryHint() {
        return recoveryHint;
    }
}
