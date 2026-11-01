package medix.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Technical details of errors go to data/error.log (never to the user). Screens show
 * {@link #describe(Throwable)}: a short, friendly sentence.
 */
public final class ErrorLog {

    public static final String FILE_NAME = "error.log";
    /** The log is started again when it grows past this size. */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private ErrorLog() {
    }

    public static File getFile() {
        return new File(AppPaths.getDataDir(), FILE_NAME);
    }

    /** Appends a time-stamped entry with the stack trace. Never throws. */
    public static synchronized void log(String context, Throwable error) {
        File file = getFile();
        boolean append = !(file.exists() && file.length() > MAX_BYTES);
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, append),
                StandardCharsets.UTF_8))) {
            out.println("[" + DateUtil.now() + "] " + context + " (thread " + Thread.currentThread().getName() + ")");
            if (error != null) {
                error.printStackTrace(out);
            }
            out.println();
        } catch (IOException | RuntimeException e) {
            // the log itself failed (e.g. read-only folder): nothing else we can do
        }
    }

    /** A message without an exception, e.g. a skipped migration. */
    public static void log(String message) {
        log(message, null);
    }

    /** Logs the error and returns a sentence for a JOptionPane. */
    public static String describe(Throwable error) {
        log("Shown to the user", error);
        if (error instanceof SQLException) {
            String m = String.valueOf(error.getMessage()).toUpperCase();
            if (m.contains("SQLITE_BUSY") || m.contains("LOCKED")) {
                return "The database is busy right now. Please try again in a moment.";
            }
            if (m.contains("SQLITE_READONLY") || m.contains("SQLITE_CANTOPEN")) {
                return "The database file cannot be written. Check that the data folder is not read-only.";
            }
            return "A database problem occurred. The details were saved to data/" + FILE_NAME + ".";
        }
        if (error instanceof IOException && error.getMessage() != null) {
            return error.getMessage();
        }
        return "Something went wrong. The details were saved to data/" + FILE_NAME + ".";
    }
}
