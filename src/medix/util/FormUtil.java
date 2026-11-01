package medix.util;

import java.awt.Frame;
import java.text.ParseException;
import javax.swing.JFrame;
import javax.swing.JSpinner;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/**
 * Small helpers for form code (outside the generated blocks).
 */
public final class FormUtil {

    private FormUtil() {
    }

    /**
     * Navigation from a small pop-up window (e.g. "Go to My Reservations"): shows `next` and
     * disposes every other open MediX frame, so only one main screen stays open.
     */
    public static void switchTo(JFrame next) {
        next.setVisible(true);
        for (Frame f : Frame.getFrames()) {
            if (f != next && f.isDisplayable() && f instanceof JFrame) {
                f.dispose();
            }
        }
    }

    /** The spinner's whole-number value, including text the user typed but did not confirm with Enter. */
    public static int intValue(JSpinner spinner) {
        try {
            spinner.commitEdit();
        } catch (ParseException e) {
            // invalid text: keep the last valid value
        }
        return ((Number) spinner.getValue()).intValue();
    }

    /** Runs the action whenever the text changes (typing, paste, setText), e.g. live search. */
    public static void onTextChange(JTextComponent field, final Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                action.run();
            }
        });
    }
}
