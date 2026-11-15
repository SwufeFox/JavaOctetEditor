package cn.enaium.joe.gui.panel;

import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.compiler.CompileError;
import cn.enaium.joe.util.compiler.CompilerDiagnostic;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.tools.Diagnostic;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compilation feedback belongs to the editor revision that produced it. */
final class CompilationDiagnosticsPanel extends JPanel {
    private final JTextArea editor;
    private final JLabel summary = new JLabel();
    private final JLabel stale = new JLabel(LangUtil.i18n("editor.diagnostics.stale"));
    private final JToggleButton warnings = new JToggleButton();
    private final JToggleButton details = new JToggleButton(LangUtil.i18n("editor.diagnostics.details"));
    private final DefaultListModel<CompilerDiagnostic> model = new DefaultListModel<>();
    private final JList<CompilerDiagnostic> entries = new JList<>(model);
    private final JScrollPane entryScroll = new JScrollPane(entries);
    private final JTextArea message = new JTextArea();
    private final JScrollPane messageScroll = new JScrollPane(message);
    private final JTextArea technical = new JTextArea();
    private final JScrollPane technicalScroll = new JScrollPane(technical);
    private final JPanel body = new JPanel(new BorderLayout(0, 4));
    private List<CompilerDiagnostic> diagnostics = List.of();
    private CompileError failure;
    private boolean currentSource;

    CompilationDiagnosticsPanel(JTextArea editor) {
        super(new BorderLayout(0, 4));
        this.editor = editor;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        header.add(summary);
        header.add(warnings);
        header.add(details);
        JButton close = new JButton(LangUtil.i18n("editor.diagnostics.close"));
        close.addActionListener(e -> clear());
        header.add(close);
        add(header, BorderLayout.NORTH);
        body.add(stale, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        entries.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entries.getAccessibleContext().setAccessibleName(LangUtil.i18n("editor.diagnostics.title"));
        entries.setCellRenderer(new DiagnosticRenderer());
        entries.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int index = entries.locationToIndex(e.getPoint());
                if (index >= 0 && entries.getCellBounds(index, index).contains(e.getPoint())) {
                    entries.setSelectedIndex(index);
                    navigate();
                }
            }
        });
        entries.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "navigateDiagnostic");
        entries.getActionMap().put("navigateDiagnostic", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { navigate(); }
        });
        warnings.addActionListener(e -> updateEntries());
        details.addActionListener(e -> {
            if (details.isSelected() && failure != null) {
                if (technical.getText().isEmpty()) {
                    StringWriter output = new StringWriter();
                    failure.printStackTrace(new PrintWriter(output));
                    technical.setText(output.toString());
                    technical.setCaretPosition(0);
                }
                add(technicalScroll, BorderLayout.SOUTH);
            } else {
                remove(technicalScroll);
            }
            revalidate();
            repaint();
        });
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        technical.setEditable(false);
        technical.getAccessibleContext().setAccessibleName(LangUtil.i18n("editor.diagnostics.details"));
        technicalScroll.setPreferredSize(new Dimension(400, 110));
        setVisible(false);
    }

    void showFailure(CompileError error, boolean currentSource) {
        clear();
        this.failure = error;
        this.currentSource = currentSource;
        List<CompilerDiagnostic> sorted = new ArrayList<>(error.getDiagnostics());
        sorted.sort(Comparator.comparingInt(d -> d.kind() == Diagnostic.Kind.ERROR ? 0 : isWarning(d) ? 2 : 1));
        diagnostics = List.copyOf(sorted);
        long errorCount = diagnostics.stream().filter(d -> d.kind() == Diagnostic.Kind.ERROR).count();
        long warningCount = diagnostics.stream().filter(CompilationDiagnosticsPanel::isWarning).count();
        summary.setText(LangUtil.i18n("editor.diagnostics.summary", errorCount, warningCount));
        warnings.setText(LangUtil.i18n("editor.diagnostics.warnings", warningCount));
        warnings.setVisible(warningCount > 0);
        stale.setVisible(!currentSource);
        entries.setEnabled(currentSource);
        if (diagnostics.isEmpty()) {
            message.setText(error.getMessage() == null || error.getMessage().isBlank()
                    ? LangUtil.i18n("editor.diagnostics.failed") : error.getMessage());
            message.setCaretPosition(0);
            messageScroll.setPreferredSize(new Dimension(400, 80));
            body.add(messageScroll, BorderLayout.CENTER);
        } else {
            body.add(entryScroll, BorderLayout.CENTER);
            updateEntries();
        }
        setVisible(true);
        revalidate();
        repaint();
    }

    void invalidateLocations() {
        if (failure == null || !currentSource) return;
        currentSource = false;
        stale.setVisible(true);
        entries.setEnabled(false);
        revalidate();
        repaint();
    }

    void clear() {
        failure = null;
        currentSource = false;
        diagnostics = List.of();
        model.clear();
        warnings.setSelected(false);
        details.setSelected(false);
        technical.setText("");
        message.setText("");
        body.remove(entryScroll);
        body.remove(messageScroll);
        remove(technicalScroll);
        setVisible(false);
        revalidate();
        repaint();
    }

    private void updateEntries() {
        model.clear();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if (!isWarning(diagnostic) || warnings.isSelected()) model.addElement(diagnostic);
        }
        entryScroll.setPreferredSize(new Dimension(400, Math.max(40, Math.min(150, model.size() * 52))));
        if (!model.isEmpty()) entries.setSelectedIndex(0);
        revalidate();
    }

    private static boolean isWarning(CompilerDiagnostic diagnostic) {
        return diagnostic.kind() == Diagnostic.Kind.WARNING || diagnostic.kind() == Diagnostic.Kind.MANDATORY_WARNING;
    }

    private void navigate() {
        if (!currentSource) return;
        CompilerDiagnostic diagnostic = entries.getSelectedValue();
        if (diagnostic == null) return;
        int offset = location(diagnostic, editor);
        if (offset < 0) return;
        editor.setCaretPosition(offset);
        editor.requestFocusInWindow();
    }

    static int location(CompilerDiagnostic diagnostic, JTextArea editor) {
        int length = editor.getDocument().getLength();
        if (diagnostic.position() >= 0 && diagnostic.position() <= length) return (int) diagnostic.position();
        if (diagnostic.line() < 1 || diagnostic.line() > editor.getLineCount() || diagnostic.column() < 1) return -1;
        try {
            int line = (int) diagnostic.line() - 1;
            int start = editor.getLineStartOffset(line);
            int end = Math.min(length, editor.getLineEndOffset(line));
            // A column may point just after the last character, but never into a newline.
            while (end > start) {
                char last = editor.getText(end - 1, 1).charAt(0);
                if (last != '\n' && last != '\r') break;
                end--;
            }
            long offset = start + diagnostic.column() - 1;
            return offset <= end ? (int) offset : -1;
        } catch (BadLocationException e) {
            return -1;
        }
    }

    private static final class DiagnosticRenderer extends JTextArea implements ListCellRenderer<CompilerDiagnostic> {
        DiagnosticRenderer() {
            setEditable(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CompilerDiagnostic> list, CompilerDiagnostic diagnostic,
                                                      int index, boolean selected, boolean focused) {
            String kind = LangUtil.i18n(diagnostic.kind() == Diagnostic.Kind.ERROR ? "editor.diagnostics.error"
                    : isWarning(diagnostic) ? "editor.diagnostics.warning" : "editor.diagnostics.note");
            String source = diagnostic.source() == null || diagnostic.source().isBlank()
                    ? LangUtil.i18n("editor.diagnostics.source") : diagnostic.source();
            String location = source;
            if (diagnostic.line() > 0) location += ":" + diagnostic.line();
            if (diagnostic.column() > 0) location += ":" + diagnostic.column();
            setText(kind + " — " + location + "\n" + diagnostic.message());
            setFont(list.getFont());
            setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            javax.swing.border.Border border = UIManager.getBorder("List.focusCellHighlightBorder");
            setBorder(focused && border != null ? BorderFactory.createCompoundBorder(border, BorderFactory.createEmptyBorder(3, 5, 3, 5))
                    : BorderFactory.createEmptyBorder(4, 6, 4, 6));
            int width = list.getWidth() > 0 ? list.getWidth() : 400;
            setSize(width, Short.MAX_VALUE);
            return this;
        }
    }
}
