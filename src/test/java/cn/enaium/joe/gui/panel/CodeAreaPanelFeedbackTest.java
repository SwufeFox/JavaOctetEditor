package cn.enaium.joe.gui.panel;

import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.compiler.CompileError;
import cn.enaium.joe.util.compiler.CompilerDiagnostic;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.tools.Diagnostic;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeAreaPanelFeedbackTest {
    @Test
    void searchNavigatesBothDirectionsWrapsAndUsesToolbarKeyboardBindings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CodeAreaPanel panel = new CodeAreaPanel();
            panel.getTextArea().setText("one two one");
            panel.getTextArea().select(0, 3);
            key(panel.getTextArea(), KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK);
            assertTrue(child(panel, JToolBar.class).isVisible());
            JTextField query = child(panel, JTextField.class);
            assertEquals("one", query.getText(), "Ctrl+F seeds the selected text");
            key(query, KeyEvent.VK_ENTER, 0);
            assertEquals(8, panel.getTextArea().getSelectionStart());
            assertFeedback(panel, LangUtil.i18n("editor.search.result", 2, 2));
            key(panel.getTextArea(), KeyEvent.VK_F3, 0);
            assertEquals(0, panel.getTextArea().getSelectionStart());
            assertFeedbackContains(panel, LangUtil.i18n("editor.search.wrappedStart"));
            JCheckBox regex = children(panel, JCheckBox.class).getFirst();
            key(regex, KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(8, panel.getTextArea().getSelectionStart());
            assertFeedbackContains(panel, LangUtil.i18n("editor.search.wrappedEnd"));
            key(panel.getTextArea(), KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(0, panel.getTextArea().getSelectionStart());
            for (JComponent origin : List.of(query, regex, children(panel, JButton.class).getFirst(), panel.getTextArea())) {
                key(panel.getTextArea(), KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK);
                key(origin, KeyEvent.VK_ESCAPE, 0);
                assertFalse(child(panel, JToolBar.class).isVisible(), "Escape closes search from any toolbar control or editor");
            }
            assertNotEquals("findNext", panel.getTextArea().getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)),
                    "Plain editor Enter remains the library's editing action");
            assertNotEquals("findPrevious", panel.getTextArea().getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)));
        });
    }

    @Test
    void searchPreservesSelectionForFailuresAndRespectsCaseAndFullDocumentRegex() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CodeAreaPanel panel = new CodeAreaPanel();
            panel.getTextArea().setText("Alpha alpha\nalpha");
            JTextField query = child(panel, JTextField.class);
            List<JCheckBox> options = children(panel, JCheckBox.class);
            panel.getTextArea().select(0, 5);
            query.setText("missing");
            key(query, KeyEvent.VK_ENTER, 0);
            assertEquals(0, panel.getTextArea().getSelectionStart());
            assertEquals(5, panel.getTextArea().getSelectionEnd());
            assertFeedback(panel, LangUtil.i18n("editor.search.noMatches"));
            options.getFirst().doClick();
            query.setText("[");
            key(query, KeyEvent.VK_ENTER, 0);
            assertEquals(0, panel.getTextArea().getSelectionStart());
            assertEquals(5, panel.getTextArea().getSelectionEnd());
            assertFeedbackContains(panel, LangUtil.i18n("editor.search.invalidRegex", ""));
            query.setText("(?<=Alpha )alpha");
            key(query, KeyEvent.VK_ENTER, 0);
            assertEquals(6, panel.getTextArea().getSelectionStart(), "Lookbehind sees text before the search cursor");
            query.setText("^alpha");
            key(query, KeyEvent.VK_ENTER, 0);
            assertEquals(panel.getTextArea().getText().indexOf('\n') + 1, panel.getTextArea().getSelectionStart(), "Anchors refer to document lines");
            options.getFirst().doClick();
            options.get(1).doClick();
            query.setText("Alpha");
            key(query, KeyEvent.VK_ENTER, 0);
            assertEquals(0, panel.getTextArea().getSelectionStart());
            assertFeedbackContains(panel, LangUtil.i18n("editor.search.result", 1, 1));
            panel.getTextArea().append(" Alpha");
            key(query, KeyEvent.VK_ENTER, 0);
            assertFeedbackContains(panel, LangUtil.i18n("editor.search.result", 2, 2));
        });
    }

    @Test
    void emptyRegexMatchesAdvanceInBothDirectionsIncludingEndOfDocument() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CodeAreaPanel panel = new CodeAreaPanel();
            panel.getTextArea().setText("aa");
            panel.getTextArea().setCaretPosition(0);
            JTextField query = child(panel, JTextField.class);
            children(panel, JCheckBox.class).getFirst().doClick();
            query.setText("^|(?<=a)");
            for (int expected : new int[]{0, 1, 2}) {
                key(query, KeyEvent.VK_ENTER, 0);
                assertEquals(expected, panel.getTextArea().getCaretPosition());
            }
            key(query, KeyEvent.VK_ENTER, 0);
            assertEquals(0, panel.getTextArea().getCaretPosition());
            assertFeedbackContains(panel, LangUtil.i18n("editor.search.wrappedStart"));
            key(query, KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(2, panel.getTextArea().getCaretPosition());
            assertFeedbackContains(panel, LangUtil.i18n("editor.search.wrappedEnd"));
            key(query, KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(1, panel.getTextArea().getCaretPosition());
            query.setText("$");
            panel.getTextArea().setText("");
            key(query, KeyEvent.VK_ENTER, 0);
            assertEquals(0, panel.getTextArea().getCaretPosition());
            assertFeedback(panel, LangUtil.i18n("editor.search.result", 1, 1));
            key(query, KeyEvent.VK_ENTER, 0);
            assertFeedbackContains(panel, LangUtil.i18n("editor.search.wrappedStart"));
        });
    }

    @Test
    void diagnosticsSortErrorsCollapseWarningsNavigateAndInvalidateOnEditWithoutLosingUndo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CodeAreaPanel panel = new CodeAreaPanel();
            panel.getTextArea().setText("first\nsecond\n");
            panel.getTextArea().discardAllEdits();
            CompilerDiagnostic warning = diagnostic(Diagnostic.Kind.WARNING, "Warning detail", 1, 1, 0);
            CompilerDiagnostic error = diagnostic(Diagnostic.Kind.ERROR, "Error detail", 2, 2, 7);
            panel.showCompilationFailure(new CompileError("Compiler output", null, List.of(warning, error)), true);
            CompilationDiagnosticsPanel diagnostics = child(panel, CompilationDiagnosticsPanel.class);
            JList<?> list = child(diagnostics, JList.class);
            assertTrue(diagnostics.isVisible());
            assertEquals(1, list.getModel().getSize());
            assertEquals(error, list.getModel().getElementAt(0));
            key(list, KeyEvent.VK_ENTER, 0);
            assertEquals(7, panel.getTextArea().getCaretPosition());
            JToggleButton warnings = children(diagnostics, JToggleButton.class).getFirst();
            warnings.doClick();
            assertEquals(2, list.getModel().getSize());
            assertEquals(error, list.getModel().getElementAt(0));
            list.setSize(400, 200);
            Rectangle warningBounds = list.getCellBounds(1, 1);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, 0, 0,
                    warningBounds.x + 2, warningBounds.y + 2, 1, false, MouseEvent.BUTTON1));
            assertEquals(0, panel.getTextArea().getCaretPosition(), "A click navigates to the warning location");
            panel.getTextArea().setCaretPosition(2);
            panel.getTextArea().append("draft");
            assertFalse(list.isEnabled(), "Document changes invalidate navigation synchronously");
            int afterEdit = panel.getTextArea().getCaretPosition();
            key(list, KeyEvent.VK_ENTER, 0);
            assertEquals(afterEdit, panel.getTextArea().getCaretPosition());
            assertTrue(children(diagnostics, JLabel.class).stream().anyMatch(label -> label.isVisible()
                    && label.getText().equals(LangUtil.i18n("editor.diagnostics.stale"))));
            panel.getTextArea().undoLastAction();
            assertEquals("first\nsecond\n", panel.getTextArea().getText());
            assertFalse(list.isEnabled(), "Undo does not silently revalidate the compiler snapshot");
            panel.clearDiagnostics();
            assertFalse(diagnostics.isVisible());
            assertEquals(0, list.getModel().getSize());
            panel.showCompilationFailure(new CompileError("No compiler is installed"), true);
            assertFalse(warnings.isSelected());
            assertTrue(children(diagnostics, JTextArea.class).stream().anyMatch(area -> area.getText().equals("No compiler is installed")));
            JToggleButton details = children(diagnostics, JToggleButton.class).get(1);
            details.doClick();
            assertTrue(children(diagnostics, JTextArea.class).stream().anyMatch(area -> area.getText().contains("CompileError: No compiler is installed")));
            panel.clearDiagnostics();
            assertFalse(diagnostics.isVisible());
        });
    }

    @Test
    void diagnosticFallbackLocationsAreValidatedAndStaleResultsCannotNavigate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CodeAreaPanel panel = new CodeAreaPanel();
            panel.getTextArea().setText("abc\r\ndef");
            JTextArea text = panel.getTextArea();
            assertEquals(1, CompilationDiagnosticsPanel.location(diagnostic(Diagnostic.Kind.ERROR, "position wins", 2, 2, 1), text));
            assertEquals(6, CompilationDiagnosticsPanel.location(diagnostic(Diagnostic.Kind.ERROR, "fallback", 2, 2, -1), text));
            assertEquals(8, CompilationDiagnosticsPanel.location(diagnostic(Diagnostic.Kind.ERROR, "EOF", -1, -1, 8), text));
            assertEquals(-1, CompilationDiagnosticsPanel.location(diagnostic(Diagnostic.Kind.ERROR, "unknown", -1, -1, -1), text));
            assertEquals(-1, CompilationDiagnosticsPanel.location(diagnostic(Diagnostic.Kind.ERROR, "invalid column", 1, 5, -1), text));
            assertEquals(-1, CompilationDiagnosticsPanel.location(diagnostic(Diagnostic.Kind.ERROR, "invalid line", 20, 1, -1), text));
            panel.showCompilationFailure(new CompileError("stale", null,
                    List.of(diagnostic(Diagnostic.Kind.ERROR, "old source", 2, 2, 6))), false);
            text.setCaretPosition(2);
            JList<?> list = child(panel, JList.class);
            key(list, KeyEvent.VK_ENTER, 0);
            assertEquals(2, text.getCaretPosition());
            assertFalse(list.isEnabled());
            panel.showCompilationFailure(new CompileError("unlocated", null,
                    List.of(diagnostic(Diagnostic.Kind.ERROR, "unknown", -1, -1, -1))), true);
            key(list, KeyEvent.VK_ENTER, 0);
            assertEquals(2, text.getCaretPosition());
            // Replacing the document must detach the old listener and invalidate this snapshot.
            panel.getTextArea().setDocument(new org.fife.ui.rsyntaxtextarea.RSyntaxDocument("text/plain"));
            assertFalse(list.isEnabled());
        });
    }

    private static CompilerDiagnostic diagnostic(Diagnostic.Kind kind, String message, long line, long column, long position) {
        return new CompilerDiagnostic(kind, "Example.java", line, column, position, message);
    }

    private static void key(JComponent component, int key, int modifiers) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key, modifiers);
        Object actionKey = component.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
        if (actionKey != null) {
            Action action = component.getActionMap().get(actionKey);
            if (action != null && action.isEnabled()) {
                action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, actionKey.toString()));
                return;
            }
        }
        Component parent = component.getParent();
        while (parent != null) {
            if (parent instanceof JComponent ancestor) {
                actionKey = ancestor.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke);
                Action action = actionKey == null ? null : ancestor.getActionMap().get(actionKey);
                if (action != null && action.isEnabled()) {
                    action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, actionKey.toString()));
                    return;
                }
            }
            parent = parent.getParent();
        }
        fail("No binding for " + stroke + " from " + component.getClass().getSimpleName());
    }

    private static void assertFeedback(CodeAreaPanel panel, String expected) {
        assertEquals(expected, child(child(panel, JToolBar.class), JLabel.class).getText());
    }

    private static void assertFeedbackContains(CodeAreaPanel panel, String expected) {
        assertTrue(child(child(panel, JToolBar.class), JLabel.class).getText().contains(expected));
    }

    private static <T extends Component> T child(Container root, Class<T> type) {
        return children(root, type).getFirst();
    }

    private static <T extends Component> List<T> children(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) result.add(type.cast(component));
            if (component instanceof Container container) result.addAll(children(container, type));
        }
        return result;
    }
}
