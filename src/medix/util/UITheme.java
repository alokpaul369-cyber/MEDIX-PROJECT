package medix.util;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.UIManager;
import medix.model.NotificationType;
import medix.model.PharmacyStatus;
import medix.model.ReservationStatus;

/**
 * MediX colours, fonts and the FlatLaf setup. Forms call the style* helpers
 * from their own code (outside the generated initComponents block).
 */
public final class UITheme {

    public static final Color SIDEBAR = Color.decode("#16333A");
    public static final Color ACCENT = Color.decode("#13806A");
    public static final Color BACKGROUND = Color.decode("#F4F6F9");
    public static final Color SUCCESS = Color.decode("#2E9E5B");
    public static final Color WARNING = Color.decode("#E0A100");
    public static final Color DANGER = Color.decode("#D64545");
    public static final Color TEXT = Color.decode("#1F2933");
    public static final Color TEXT_MUTED = Color.decode("#6B7785");
    public static final Color WHITE = Color.WHITE;
    public static final Color ORANGE = Color.decode("#E07B00");
    public static final Color TEAL = Color.decode("#178A97");
    public static final Color PURPLE = Color.decode("#7E57C2");
    /** Inventory row colours: low stock, expiring within 60 days, expired. */
    public static final Color ROW_LOW_STOCK = Color.decode("#FFF6DA");
    public static final Color ROW_EXPIRING = Color.decode("#FFE6CC");
    public static final Color ROW_EXPIRED = Color.decode("#FDE0E0");
    public static final Color CARD_BORDER = Color.decode("#DDE2E8");
    public static final Color SIDEBAR_HOVER = Color.decode("#1F4750");
    public static final Color NOTE_WARNING_BG = Color.decode("#FFF6DA");
    public static final Color NOTE_WARNING_BORDER = Color.decode("#F0D98A");
    public static final Color NOTE_WARNING_TEXT = Color.decode("#6D5300");
    public static final Color NOTE_INFO_BG = Color.decode("#E6F4EF");
    public static final Color NOTE_INFO_BORDER = Color.decode("#B5DCCB");
    public static final Color NOTE_INFO_TEXT = Color.decode("#0E5A4B");

    public static final String FONT_FAMILY = "Segoe UI";
    public static final Font FONT_BODY = new Font(FONT_FAMILY, Font.PLAIN, 13);
    public static final Font FONT_BODY_BOLD = new Font(FONT_FAMILY, Font.BOLD, 13);
    public static final Font FONT_HEADING = new Font(FONT_FAMILY, Font.BOLD, 16);
    public static final Font FONT_TITLE = new Font(FONT_FAMILY, Font.BOLD, 22);

    private UITheme() {
    }

    /** Installs FlatLaf with the MediX colours. Call once in Main before any frame is created. */
    public static void install() {
        Map<String, String> extra = new HashMap<String, String>();
        extra.put("@accentColor", "#13806A");
        extra.put("@background", "#F4F6F9");
        FlatLaf.setGlobalExtraDefaults(extra);
        if (!FlatLightLaf.setup()) {
            ErrorLog.log("FlatLaf could not be installed; using the default look and feel.");
        }
        UIManager.put("defaultFont", FONT_BODY);
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("Table.selectionBackground", ACCENT);
        UIManager.put("Table.selectionForeground", WHITE);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("TableHeader.font", FONT_BODY_BOLD);
        UIManager.put("OptionPane.messageFont", FONT_BODY);
        UIManager.put("OptionPane.buttonFont", FONT_BODY);
        UIManager.put("TabbedPane.selectedForeground", ACCENT);
        UIManager.put("TabbedPane.underlineColor", ACCENT);
        UIManager.put("TabbedPane.font", FONT_BODY_BOLD);
    }

    /** Window icon and background for every MediX frame. Call from the frame's applyTheme(). */
    public static void styleFrame(JFrame frame) {
        frame.setIconImages(Arrays.asList(createLogoImage(16), createLogoImage(32), createLogoImage(64)));
        frame.getContentPane().setBackground(BACKGROUND);
    }

    /** "✕" if the UI font can draw it, otherwise "×" (Java does not fall back to other fonts). */
    public static String cross() {
        return FONT_BODY.canDisplay('✕') ? "✕" : "×";
    }

    /** "⚠" if the UI font can draw it, otherwise "!". */
    public static String warnSign() {
        return FONT_BODY.canDisplay('⚠') ? "⚠" : "!";
    }

    /** Real pixels per CSS "px" in HTML labels (measured once, see cssPixelRatio). */
    private static float cssPixelRatio;

    /**
     * HTML text that wraps at `pixels` real pixels (the same pixels as the GroupLayout sizes in
     * the .form). Swing renders style='width:Npx' wider than N pixels (how much depends on the
     * screen and font scaling), so the CSS width is converted with a measured ratio.
     * `html` may contain tags but no &lt;html&gt;/&lt;body&gt;.
     */
    public static String wrapHtml(String html, int pixels) {
        int css = Math.max(40, Math.round(pixels / cssPixelRatio()));
        return "<html><body style='width:" + css + "px'>" + html + "</body></html>";
    }

    /** Lays out a test label whose body is 200 CSS px wide and returns its real width / 200. */
    private static synchronized float cssPixelRatio() {
        if (cssPixelRatio <= 0) {
            JLabel probe = new JLabel("<html><body style='width:200px'>" + new String(new char[60]).replace("\0", "word ")
                    + "</body></html>");
            probe.setFont(FONT_BODY);
            float ratio = probe.getPreferredSize().width / 200f;
            cssPixelRatio = ratio > 0.5f && ratio < 3f ? ratio : 1f;
        }
        return cssPixelRatio;
    }

    /** Windows font that has symbols Segoe UI lacks (⚠, ✕). */
    public static final String SYMBOL_FONT = "Segoe UI Symbol";

    /**
     * A symbol for use INSIDE an &lt;html&gt; label or table cell: drawn with the UI font if it has
     * the glyph, else with Segoe UI Symbol, else the fallback text. Java does not borrow missing
     * glyphs from other fonts by itself.
     */
    public static String symbolHtml(char symbol, String fallback) {
        if (FONT_BODY.canDisplay(symbol)) {
            return String.valueOf(symbol);
        }
        Font symbols = new Font(SYMBOL_FONT, Font.PLAIN, FONT_BODY.getSize());
        if (SYMBOL_FONT.equalsIgnoreCase(symbols.getFamily()) && symbols.canDisplay(symbol)) {
            return "<font face='" + SYMBOL_FONT + "'>" + symbol + "</font>";
        }
        return fallback;
    }

    /** ⚠ for html text (stale stock). */
    public static String warnHtml() {
        return symbolHtml('⚠', "!");
    }

    /** ✕ for html text (expired stock). */
    public static String crossHtml() {
        return symbolHtml('✕', "×");
    }

    /**
     * Reservation teal, Low stock amber, Expiry orange (red once the batch has expired), Stale grey,
     * Verification green, Report purple, Info grey.
     */
    public static Color notificationTypeColor(NotificationType type, String message) {
        if (type == null) {
            return TEXT_MUTED;
        }
        switch (type) {
            case RESERVATION:
                return TEAL;
            case LOW_STOCK:
                return WARNING;
            case EXPIRY:
                return message != null && message.contains("has expired") ? DANGER : ORANGE;
            case VERIFICATION:
                return SUCCESS;
            case REPORT:
                return PURPLE;
            default:
                return TEXT_MUTED;
        }
    }

    /** Pending amber, Confirmed teal, Ready green, Completed grey, Cancelled / Expired red. */
    public static Color reservationStatusColor(ReservationStatus status) {
        if (status == null) {
            return TEXT_MUTED;
        }
        switch (status) {
            case PENDING:
                return WARNING;
            case CONFIRMED:
                return TEAL;
            case READY:
                return SUCCESS;
            case COMPLETED:
                return TEXT_MUTED;
            default:
                return DANGER;
        }
    }

    /** Pending amber, Verified green, Rejected red, Suspended grey. */
    public static Color pharmacyStatusColor(PharmacyStatus status) {
        if (status == null) {
            return TEXT_MUTED;
        }
        switch (status) {
            case PENDING:
                return WARNING;
            case VERIFIED:
                return SUCCESS;
            case REJECTED:
                return DANGER;
            default:
                return TEXT_MUTED;
        }
    }

    /** The MediX logo: a white "+" in a green circle. */
    public static ImageIcon createLogoIcon(int size) {
        return new ImageIcon(createLogoImage(size));
    }

    public static BufferedImage createLogoImage(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(ACCENT);
        g.fillOval(0, 0, size - 1, size - 1);
        g.setColor(WHITE);
        int arm = Math.max(2, size / 7);
        int len = size * 5 / 9;
        int off = (size - len) / 2;
        int mid = (size - arm) / 2;
        g.fillRect(off, mid, len, arm);
        g.fillRect(mid, off, arm, len);
        g.dispose();
        return img;
    }

    /** White card with a light border, used for the panels inside a JTabbedPane. */
    public static void styleCard(JComponent card) {
        card.setOpaque(true);
        card.setBackground(WHITE);
        card.setBorder(BorderFactory.createLineBorder(CARD_BORDER));
    }

    /** Dashboard summary card: white, light border with a 3 px coloured top border. */
    public static void styleSummaryCard(JComponent card, Color top) {
        card.setOpaque(true);
        card.setBackground(WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(3, 0, 0, 0, top),
                BorderFactory.createMatteBorder(0, 1, 1, 1, CARD_BORDER)));
    }

    /** "#RRGGBB" for use inside HTML text. */
    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** A flat, borderless button that looks like a hyperlink. */
    public static void styleLinkButton(JButton button) {
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setForeground(ACCENT);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** Red error text under a form. */
    public static void styleError(JLabel label) {
        label.setFont(FONT_BODY);
        label.setForeground(DANGER);
    }

    /** Yellow information box, e.g. "Your pharmacy stays hidden ...". */
    public static void styleWarningNote(JLabel label) {
        styleNote(label, NOTE_WARNING_BG, NOTE_WARNING_BORDER, NOTE_WARNING_TEXT);
    }

    /** Light green information box. */
    public static void styleInfoNote(JLabel label) {
        styleNote(label, NOTE_INFO_BG, NOTE_INFO_BORDER, NOTE_INFO_TEXT);
    }

    private static void styleNote(JLabel label, Color bg, Color border, Color text) {
        label.setOpaque(true);
        label.setBackground(bg);
        label.setForeground(text);
        label.setFont(FONT_BODY);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    public static void styleTitle(JLabel label) {
        label.setFont(FONT_TITLE);
        label.setForeground(SIDEBAR);
    }

    public static void styleHeading(JLabel label) {
        label.setFont(FONT_HEADING);
        label.setForeground(SIDEBAR);
    }

    public static void styleMuted(JLabel label) {
        label.setFont(FONT_BODY);
        label.setForeground(TEXT_MUTED);
    }

    /** Coloured status text, e.g. styleStatus(lblStatus, UITheme.SUCCESS). */
    public static void styleStatus(JLabel label, Color color) {
        label.setFont(FONT_BODY_BOLD);
        label.setForeground(color);
    }

    public static void stylePrimaryButton(JButton button) {
        styleFilledButton(button, ACCENT);
    }

    public static void styleSuccessButton(JButton button) {
        styleFilledButton(button, SUCCESS);
    }

    public static void styleWarningButton(JButton button) {
        styleFilledButton(button, WARNING);
    }

    public static void styleDangerButton(JButton button) {
        styleFilledButton(button, DANGER);
    }

    /** Dark sidebar strip with white text on its child labels/buttons. */
    public static void styleSidebar(JComponent sidebar) {
        sidebar.setOpaque(true);
        sidebar.setBackground(SIDEBAR);
        sidebar.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        for (java.awt.Component child : sidebar.getComponents()) {
            child.setForeground(WHITE);
            if (child instanceof JButton) {
                JButton b = (JButton) child;
                b.setBackground(SIDEBAR);
                b.setBorderPainted(false);
                b.setFocusPainted(false);
                b.setHorizontalAlignment(JButton.LEFT);
            }
        }
    }

    private static void styleFilledButton(JButton button, Color color) {
        button.setBackground(color);
        button.setForeground(WHITE);
        button.setFont(FONT_BODY_BOLD);
        button.setFocusPainted(false);
    }
}
