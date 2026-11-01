package medix.util;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Stores uploaded files under data/licenses and opens them again.
 * Paths saved in the database are relative to the app folder, e.g.
 * "data/licenses/pharmacy_3_license.pdf".
 */
public final class FileStorageUtil {

    public static final long MAX_LICENSE_BYTES = 5L * 1024 * 1024;
    public static final String[] LICENSE_EXTENSIONS = {"pdf", "png", "jpg", "jpeg"};

    private FileStorageUtil() {
    }

    /** Problem with the chosen license file, or null if it is fine. */
    public static String checkLicenseFile(File file) {
        if (file == null || !file.isFile()) {
            return "Choose your license document (PDF, PNG or JPG).";
        }
        if (!hasAllowedExtension(file.getName())) {
            return "The license document must be a PDF, PNG, JPG or JPEG file.";
        }
        if (file.length() > MAX_LICENSE_BYTES) {
            return "The license document is larger than 5 MB. Choose a smaller file.";
        }
        return null;
    }

    public static boolean hasAllowedExtension(String fileName) {
        String lower = fileName.toLowerCase();
        for (String ext : LICENSE_EXTENSIONS) {
            if (lower.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies the file to data/licenses/pharmacy_&lt;id&gt;_&lt;file name&gt;
     * and returns that path relative to the app folder (with forward slashes).
     */
    public static String copyLicense(File source, int pharmacyId) throws IOException {
        String name = "pharmacy_" + pharmacyId + "_" + safeFileName(source.getName());
        File target = new File(AppPaths.getLicensesDir(), name);
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return "data/licenses/" + name;
    }

    /** Turns a stored relative path back into a file. */
    public static File resolve(String relativePath) {
        File f = new File(relativePath);
        return f.isAbsolute() ? f : new File(AppPaths.getBaseDir(), relativePath);
    }

    /** Deletes a stored file; used to clean up when a registration fails. */
    public static void deleteQuietly(String relativePath) {
        if (relativePath != null) {
            File f = resolve(relativePath);
            if (f.isFile() && !f.delete()) {
                f.deleteOnExit();
            }
        }
    }

    /** Opens the stored file with the system's default program. */
    public static void open(String relativePath) throws IOException {
        File f = resolve(relativePath);
        if (!f.isFile()) {
            throw new IOException("File not found: " + f.getAbsolutePath());
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Opening files is not supported on this computer.");
        }
        Desktop.getDesktop().open(f);
    }

    private static String safeFileName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? "document" : cleaned;
    }
}
