package medix.util;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Locale;
import javax.swing.AbstractButton;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

/**
 * Opens a pharmacy location on OpenStreetMap in the system browser.
 */
public final class MapUtil {

    private static final String HOST = "www.openstreetmap.org";
    private static final int TIMEOUT_MILLIS = 3000;

    private MapUtil() {
    }

    /** https://www.openstreetmap.org/?mlat=..&amp;mlon=..#map=17/../.. (marker at the point, zoom 17). */
    public static String mapUrl(double lat, double lon) {
        return String.format(Locale.US, "https://%s/?mlat=%.5f&mlon=%.5f#map=17/%.5f/%.5f", HOST, lat, lon, lat, lon);
    }

    /**
     * True if openstreetmap.org can be reached (a plain TCP connection, 3-second timeout).
     * Slow when offline: call it off the Swing thread (e.g. in a SwingWorker).
     */
    public static boolean isOnline() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, 443), TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Checks the connection in the background, then opens the map; when offline (or no browser)
     * shows a friendly JOptionPane with `where` (name, coordinates, address, phone) instead.
     * Call on the Swing thread; `button` is disabled while checking.
     */
    public static void openMapOrExplain(final Component parent, final AbstractButton button, final String title,
            final double lat, final double lon, final String where) {
        button.setEnabled(false);
        parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return isOnline();
            }

            @Override
            protected void done() {
                parent.setCursor(Cursor.getDefaultCursor());
                button.setEnabled(true);
                boolean online;
                try {
                    online = get();
                } catch (Exception e) {
                    online = false;
                }
                if (!online) {
                    JOptionPane.showMessageDialog(parent, "You seem to be offline, so the map cannot open.\n\n" + where,
                            title, JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                try {
                    openMap(lat, lon);
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(parent, "Could not open the map: " + e.getMessage() + "\n\n" + where,
                            title, JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }.execute();
    }

    /** Opens the map in the default browser. */
    public static void openMap(double lat, double lon) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("This computer cannot open a web browser from MediX.");
        }
        Desktop.getDesktop().browse(URI.create(mapUrl(lat, lon)));
    }
}
