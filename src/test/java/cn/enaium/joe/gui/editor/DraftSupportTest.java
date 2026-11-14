package cn.enaium.joe.gui.editor;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DraftSupportTest {
    @Test
    void consentRejectsChangedOrNewDirtyDraftsButAllowsCleanOrClosedDrafts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel();
            Draft first = new Draft();
            first.edit();
            root.add(first);
            DraftSupport.Consent original = new DraftSupport.Consent(root);
            assertTrue(original.isValid());
            first.edit();
            assertFalse(original.isValid());
            first.dirty = false;
            assertTrue(original.isValid());
            Draft added = new Draft();
            root.add(added);
            assertTrue(original.isValid());
            added.edit();
            assertFalse(original.isValid());
            root.remove(added);
            assertTrue(original.isValid());
            first.edit();
            DraftSupport.Consent closing = new DraftSupport.Consent(root);
            root.remove(first);
            first.edit();
            assertTrue(closing.isValid());
        });
    }

    @Test
    void asynchronousFailurePreservesDraftAndNewerEditsPreventSuccessfulSaveAll() throws Exception {
        AtomicReference<JPanel> root = new AtomicReference<>();
        AtomicReference<Draft> draft = new AtomicReference<>();
        AtomicReference<CompletableFuture<Boolean>> saving = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            root.set(new JPanel());
            draft.set(new Draft());
            draft.get().edit();
            root.get().add(draft.get());
            saving.set(DraftSupport.saveAll(root.get()));
            assertFalse(saving.get().isDone());
        });
        draft.get().pending.completeExceptionally(new IllegalStateException("compile failed"));
        assertFalse(saving.get().get(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(draft.get().hasDraft());
            draft.get().pending = new CompletableFuture<>();
            saving.set(DraftSupport.saveAll(root.get()));
            draft.get().edit();
        });
        draft.get().pending.complete(true);
        assertFalse(saving.get().get(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> assertTrue(draft.get().hasDraft()));
    }

    @Test
    void refreshAndOlderAppliedTextNeverEraseANewerBuffer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea text = new JTextArea("original");
            DraftSupport.TextState state = new DraftSupport.TextState(text, () -> { });
            text.setText("captured source");
            long capturedRevision = state.revision();
            text.setText("newer source");
            state.replaceClean("background refresh");
            assertEquals("newer source", text.getText());
            state.applied("captured source");
            assertTrue(state.isDirty());
            assertEquals("newer source", text.getText());
            assertTrue(state.revision() > capturedRevision);
            text.setText("captured source");
            assertFalse(state.isDirty());
            text.setCaretPosition(2);
            text.moveCaretPosition(5);
            state.replaceClean("refreshed source");
            assertEquals(2, text.getSelectionStart());
            assertEquals(5, text.getSelectionEnd());
            assertFalse(state.isDirty());
            assertTrue(state.revision() > capturedRevision);
        });
    }

    private static final class Draft extends JPanel implements EditorDraft {
        private boolean dirty;
        private long revision;
        private CompletableFuture<Boolean> pending = new CompletableFuture<>();

        void edit() { dirty = true; revision++; }
        public boolean hasDraft() { return dirty; }
        public long draftRevision() { return revision; }
        public String draftName() { return "test source"; }
        public CompletableFuture<Boolean> saveDraft() { return pending; }
    }
}
