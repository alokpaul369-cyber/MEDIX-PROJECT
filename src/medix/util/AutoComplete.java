package medix.util;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

/**
 * Suggestion list under a text field (a JPopupMenu holding a JList, created in code –
 * it is a popup, not a screen). Up/Down move, Enter or a click picks, Escape closes.
 *
 * <pre>
 * AutoComplete.attach(txtMedicine, text -&gt; service.suggest(text), m -&gt; label(m), m -&gt; selected = m);
 * </pre>
 * onSelect receives the picked item, or null as soon as the user edits the text again.
 */
public final class AutoComplete<T> {

    /** Finds suggestions for the typed text (may throw, e.g. SQLException). */
    public interface Source<T> {
        List<T> find(String text) throws Exception;
    }

    public static final int MAX_ROWS = 8;

    private final JTextField field;
    private final Source<T> source;
    private final Function<T, String> label;
    private final Consumer<T> onSelect;
    private final DefaultListModel<T> model = new DefaultListModel<T>();
    private final JList<T> list = new JList<T>(model);
    private final JPopupMenu popup = new JPopupMenu();
    private boolean adjusting;
    private int minLetters = 1;

    private AutoComplete(JTextField field, Source<T> source, Function<T, String> label, Consumer<T> onSelect) {
        this.field = field;
        this.source = source;
        this.label = label;
        this.onSelect = onSelect;
        build();
    }

    public static <T> AutoComplete<T> attach(JTextField field, Source<T> source, Function<T, String> label,
            Consumer<T> onSelect) {
        return new AutoComplete<T>(field, source, label, onSelect);
    }

    /** Same, but suggestions only appear once at least minLetters are typed. */
    public static <T> AutoComplete<T> attach(JTextField field, int minLetters, Source<T> source,
            Function<T, String> label, Consumer<T> onSelect) {
        AutoComplete<T> ac = new AutoComplete<T>(field, source, label, onSelect);
        ac.minLetters = minLetters;
        return ac;
    }

    /** Puts text in the field without opening suggestions or clearing the selection. */
    public void setTextSilently(String text) {
        adjusting = true;
        try {
            field.setText(text);
        } finally {
            adjusting = false;
        }
        hide();
    }

    public void hide() {
        popup.setVisible(false);
    }

    private void build() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setFont(UITheme.FONT_BODY);
        list.setSelectionBackground(UITheme.ACCENT);
        list.setSelectionForeground(UITheme.WHITE);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            @SuppressWarnings("unchecked")
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, label.apply((T) value), index, selected, focus);
                setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    pick(model.get(i));
                }
            }
        });
        popup.setFocusable(false);
        popup.setBorder(BorderFactory.createLineBorder(UITheme.CARD_BORDER));
        popup.add(list);

        FormUtil.onTextChange(field, () -> {
            if (!adjusting) {
                onSelect.accept(null);
                // update after the document event has finished
                javax.swing.SwingUtilities.invokeLater(this::refresh);
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!popup.isVisible()) {
                    if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                        refresh();
                    }
                    return;
                }
                int i = list.getSelectedIndex();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN:
                        list.setSelectedIndex(Math.min(model.size() - 1, i + 1));
                        e.consume();
                        break;
                    case KeyEvent.VK_UP:
                        list.setSelectedIndex(Math.max(0, i - 1));
                        e.consume();
                        break;
                    case KeyEvent.VK_ENTER:
                        if (i >= 0) {
                            pick(model.get(i));
                            e.consume();
                        }
                        break;
                    case KeyEvent.VK_ESCAPE:
                        hide();
                        e.consume();
                        break;
                    default:
                        break;
                }
            }
        });
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                hide();
            }
        });
    }

    private void refresh() {
        String text = field.getText().trim();
        if (text.length() < Math.max(1, minLetters) || !field.isShowing() || !field.isEnabled()) {
            hide();
            return;
        }
        List<T> found;
        try {
            found = source.find(text);
        } catch (Exception e) {
            hide();
            return;
        }
        model.clear();
        for (int i = 0; i < found.size() && i < MAX_ROWS; i++) {
            model.addElement(found.get(i));
        }
        if (model.isEmpty()) {
            hide();
            return;
        }
        list.setVisibleRowCount(model.size());
        list.setSelectedIndex(0);
        popup.setPopupSize(new Dimension(field.getWidth(), list.getPreferredSize().height + 2));
        popup.show(field, 0, field.getHeight());
        field.requestFocusInWindow();
    }

    private void pick(T item) {
        setTextSilently(label.apply(item));
        onSelect.accept(item);
    }
}
