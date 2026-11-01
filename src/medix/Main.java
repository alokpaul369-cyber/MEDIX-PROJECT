package medix;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import medix.engine.JobScheduler;
import medix.engine.Jobs;
import medix.forms.LoginFrame;
import medix.service.DatabaseService;
import medix.util.AppPaths;
import medix.util.ErrorLog;
import medix.util.NotificationWatcher;
import medix.util.UITheme;

/**
 * MediX – Medicine Availability Finder. Sets up the look and feel, creates the
 * database, checks the schema, starts the background jobs (stopped on exit) and opens the first frame.
 */
public class Main {

    public static void main(String[] args) {
        installErrorHandler();
        UITheme.install();
        try {
            DatabaseService database = new DatabaseService();
            database.initialize();
            for (String line : database.checkSchema()) {
                System.out.println(line);
            }
            Jobs.registerAll();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                JobScheduler.stop();
                NotificationWatcher.removeTrayIcon();
            }, "medix-shutdown"));
        } catch (Exception e) {
            ErrorLog.log("Start-up failed", e);
            JOptionPane.showMessageDialog(null,
                    "MediX could not open its database:\n" + AppPaths.getDatabaseFile().getAbsolutePath()
                    + "\n\nClose other copies of MediX and check that the folder is not read-only."
                    + "\nThe details were saved to data/" + ErrorLog.FILE_NAME + ".",
                    "MediX – Startup error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new LoginFrame().setVisible(true);
            }
        });
    }

    /**
     * Any exception nobody caught (Swing or a background thread) goes to data/error.log; the
     * user sees a short message instead of a stack trace.
     */
    private static void installErrorHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            ErrorLog.log("Unexpected error", error);
            if (SwingUtilities.isEventDispatchThread()) {
                JOptionPane.showMessageDialog(null, "Something unexpected went wrong. Please try again.\n"
                        + "The details were saved to data/" + ErrorLog.FILE_NAME + ".",
                        "MediX – Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
