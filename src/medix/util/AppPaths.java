package medix.util;

import java.io.File;
import java.net.URISyntaxException;

/**
 * Resolves where MediX keeps its files.
 * <ul>
 *   <li>Running from NetBeans (classes folder): the project folder.</li>
 *   <li>Running the JAR: the folder that contains the JAR.</li>
 * </ul>
 * Under that base folder: data/medix.db and data/licenses/.
 */
public final class AppPaths {

    private static File baseDir;

    private AppPaths() {
    }

    public static synchronized File getBaseDir() {
        if (baseDir == null) {
            baseDir = resolveBaseDir();
        }
        return baseDir;
    }

    public static File getDataDir() {
        return ensureDir(new File(getBaseDir(), "data"));
    }

    public static File getLicensesDir() {
        return ensureDir(new File(getDataDir(), "licenses"));
    }

    public static File getDatabaseFile() {
        return new File(getDataDir(), "medix.db");
    }

    private static File resolveBaseDir() {
        try {
            File location = new File(AppPaths.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            String file = location.getName().toLowerCase();
            // MediX.jar / MediX-all.jar, or MediX.exe when Launch4j wraps the jar
            if (location.isFile() && (file.endsWith(".jar") || file.endsWith(".exe"))) {
                return location.getAbsoluteFile().getParentFile();
            }
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            // fall back to the working directory below
        }
        // NetBeans runs the project with the project folder as working directory.
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    private static File ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create folder: " + dir.getAbsolutePath());
        }
        return dir;
    }
}
