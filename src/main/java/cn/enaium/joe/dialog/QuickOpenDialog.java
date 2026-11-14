package cn.enaium.joe.dialog;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.jar.ArchiveEntryIndex;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Search immutable archive names without retaining any class or resource bytes. */
public final class QuickOpenDialog extends JDialog {
    private static final int PAGE_SIZE = 100;
    private final JavaOctetEditor editor;
    private final JTextField query = new JTextField();
    private final JComboBox<String> kind = new JComboBox<>(new String[] {
            LangUtil.i18n("navigation.all"), LangUtil.i18n("navigation.classes"), LangUtil.i18n("navigation.resources")
    });
    private final DefaultListModel<ArchiveEntryIndex.Entry> results = new DefaultListModel<>();
    private final JList<ArchiveEntryIndex.Entry> list = new JList<>(results);
    private final JLabel status = new JLabel(" ");
    private final JButton open = new JButton(LangUtil.i18n("navigation.open"));
    private final JButton more = new JButton(LangUtil.i18n("navigation.more"));
    private final Timer debounce;
    private SwingWorker<ArchiveEntryIndex.Page, Void> worker;
    private ArchiveEntryIndex index;
    private Jar archive;
    private long generation;
    private int nextOffset;
    private boolean disposed;

    private QuickOpenDialog(JavaOctetEditor editor) {
        super(editor.window, LangUtil.i18n("navigation.quickOpen"), false);
        this.editor = editor;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        debounce = new Timer(180, event -> search(0));
        debounce.setRepeats(false);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        JPanel search = new JPanel(new BorderLayout(8, 0));
        query.putClientProperty("JTextField.placeholderText", LangUtil.i18n("navigation.quickPlaceholder"));
        query.getAccessibleContext().setAccessibleName(LangUtil.i18n("navigation.quickOpen"));
        query.setToolTipText(LangUtil.i18n("navigation.quickHint"));
        kind.getAccessibleContext().setAccessibleName(LangUtil.i18n("navigation.entryKind"));
        search.add(query, BorderLayout.CENTER);
        search.add(kind, BorderLayout.EAST);
        content.add(search, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new EntryRenderer());
        list.getAccessibleContext().setAccessibleName(LangUtil.i18n("navigation.results"));
        list.addListSelectionListener(event -> open.setEnabled(list.getSelectedValue() != null));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int row = list.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2 && row >= 0
                        && list.getCellBounds(row, row).contains(event.getPoint())) openSelected();
            }
        });
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.add(status, BorderLayout.NORTH);
        JLabel hint = new JLabel(LangUtil.i18n("navigation.keyboardHint"));
        footer.add(hint, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        more.addActionListener(event -> search(nextOffset));
        open.addActionListener(event -> openSelected());
        JButton cancel = new JButton(LangUtil.i18n("navigation.cancel"));
        cancel.addActionListener(event -> dispose());
        buttons.add(more);
        buttons.add(cancel);
        buttons.add(open);
        footer.add(buttons, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { scheduleSearch(); }
            @Override public void removeUpdate(DocumentEvent event) { scheduleSearch(); }
            @Override public void changedUpdate(DocumentEvent event) { scheduleSearch(); }
        });
        kind.addActionListener(event -> scheduleSearch());
        bind(query, "DOWN", "nextResult", () -> moveSelection(1));
        bind(query, "UP", "previousResult", () -> moveSelection(-1));
        bind(query, "ENTER", "openResult", this::openSelected);
        bind(list, "ENTER", "openResult", this::openSelected);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { dispose(); }
        });
        getRootPane().setDefaultButton(open);
        setMinimumSize(new Dimension(500, 320));
        setSize(720, 480);
        setLocationRelativeTo(editor.window);
        refreshForArchive();
    }

    public static void show(JavaOctetEditor editor) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(editor));
            return;
        }
        if (editor.window == null) return;
        for (Window window : editor.window.getOwnedWindows()) {
            if (window instanceof QuickOpenDialog dialog && dialog.isDisplayable()) {
                dialog.setVisible(true);
                dialog.toFront();
                dialog.query.requestFocusInWindow();
                dialog.query.selectAll();
                return;
            }
        }
        QuickOpenDialog dialog = new QuickOpenDialog(editor);
        dialog.setVisible(true);
        dialog.query.requestFocusInWindow();
    }

    /** Called on archive replacement/refresh; old workers cannot publish into the new document. */
    public void refreshForArchive() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshForArchive);
            return;
        }
        if (disposed) return;
        invalidateSearch();
        archive = editor.getJar();
        index = archive == null ? null : ArchiveEntryIndex.capture(archive);
        if (index == null) status.setText(LangUtil.i18n("navigation.noArchive"));
        else search(0);
    }

    private void scheduleSearch() {
        invalidateSearch();
        if (disposed) return;
        if (editor.getJar() != archive) {
            refreshForArchive();
            return;
        }
        if (index == null) {
            status.setText(LangUtil.i18n("navigation.noArchive"));
            return;
        }
        status.setText(LangUtil.i18n("navigation.searching"));
        debounce.restart();
    }

    private void invalidateSearch() {
        ++generation;
        debounce.stop();
        if (worker != null) worker.cancel(true);
        worker = null;
        results.clear();
        open.setEnabled(false);
        more.setEnabled(false);
        nextOffset = 0;
    }

    private void search(int offset) {
        if (disposed || index == null) return;
        if (editor.getJar() != archive) {
            refreshForArchive();
            return;
        }
        long request = generation;
        ArchiveEntryIndex captured = index;
        Jar capturedArchive = archive;
        String text = query.getText();
        Set<ArchiveEntryIndex.Kind> kinds = switch (kind.getSelectedIndex()) {
            case 1 -> EnumSet.of(ArchiveEntryIndex.Kind.CLASS);
            case 2 -> EnumSet.of(ArchiveEntryIndex.Kind.RESOURCE);
            default -> EnumSet.allOf(ArchiveEntryIndex.Kind.class);
        };
        more.setEnabled(false);
        status.setText(LangUtil.i18n("navigation.searching"));
        worker = new SwingWorker<>() {
            @Override protected ArchiveEntryIndex.Page doInBackground() {
                return captured.search(text, kinds, offset, PAGE_SIZE);
            }

            @Override protected void done() {
                if (isCancelled() || disposed || request != generation || index != captured
                        || !editor.isCurrentArchive(capturedArchive)) return;
                worker = null;
                try {
                    ArchiveEntryIndex.Page page = get();
                    if (offset == 0) results.clear();
                    for (ArchiveEntryIndex.Entry entry : page.entries()) results.addElement(entry);
                    nextOffset = page.nextOffset();
                    more.setEnabled(page.hasMore());
                    if (results.isEmpty()) status.setText(LangUtil.i18n(text.isBlank()
                            ? "navigation.noEntries" : "navigation.noMatches"));
                    else {
                        status.setText(LangUtil.i18n("navigation.resultCount", results.size(), page.total()));
                        if (list.getSelectedIndex() < 0) list.setSelectedIndex(0);
                    }
                } catch (CancellationException ignored) {
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    status.setText(LangUtil.i18n("navigation.searchFailed"));
                    MessageUtil.error(LangUtil.i18n("navigation.searchFailed"), failure.getCause());
                }
            }
        };
        worker.execute();
    }

    private void openSelected() {
        ArchiveEntryIndex.Entry selected = list.getSelectedValue();
        if (selected == null) return;
        if (!editor.isCurrentArchive(archive)) {
            refreshForArchive();
            return;
        }
        if (editor.fileTree.openEntry(selected.path())) dispose();
        else {
            refreshForArchive();
            status.setText(LangUtil.i18n("navigation.entryGone"));
        }
    }

    private void moveSelection(int direction) {
        if (results.isEmpty()) return;
        int selected = list.getSelectedIndex();
        int next = selected < 0 ? 0 : Math.max(0, Math.min(results.size() - 1, selected + direction));
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    private static void bind(JComponent component, String stroke, String name, Runnable action) {
        component.getInputMap().put(KeyStroke.getKeyStroke(stroke), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    @Override public void dispose() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::dispose);
            return;
        }
        disposed = true;
        invalidateSearch();
        archive = null;
        index = null;
        super.dispose();
    }

    private static final class EntryRenderer extends JPanel implements ListCellRenderer<ArchiveEntryIndex.Entry> {
        private final JLabel title = new JLabel();
        private final JLabel path = new JLabel();

        private EntryRenderer() {
            super(new BorderLayout(4, 2));
            setBorder(new EmptyBorder(5, 8, 5, 8));
            title.putClientProperty("html.disable", Boolean.TRUE);
            path.putClientProperty("html.disable", Boolean.TRUE);
            add(title, BorderLayout.NORTH);
            add(path, BorderLayout.SOUTH);
        }

        @Override public Component getListCellRendererComponent(JList<? extends ArchiveEntryIndex.Entry> list,
                ArchiveEntryIndex.Entry entry, int index, boolean selected, boolean focus) {
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            title.setForeground(foreground);
            Color secondary = UIManager.getColor("Label.disabledForeground");
            path.setForeground(selected || secondary == null ? foreground : secondary);
            title.setFont(list.getFont());
            path.setFont(list.getFont());
            title.setText(entry.displayName() + "  ·  " + LangUtil.i18n(entry.kind() == ArchiveEntryIndex.Kind.CLASS
                    ? "navigation.class" : "navigation.resource"));
            path.setText(entry.path());
            setToolTipText(entry.path());
            return this;
        }
    }
}
