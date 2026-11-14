package cn.enaium.joe.gui.editor;

import cn.enaium.joe.util.LangUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Draft traversal and optimistic consent; callers commit guarded actions on the EDT. */
public final class DraftSupport {
    private DraftSupport() { }

    public static List<EditorDraft> collect(Component root) {
        List<EditorDraft> drafts = new ArrayList<>();
        collect(root, drafts);
        return List.copyOf(drafts);
    }

    private static void collect(Component component, List<EditorDraft> drafts) {
        if (component instanceof EditorDraft draft) drafts.add(draft);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, drafts);
        }
    }

    public static boolean hasDrafts(Component root) {
        return collect(root).stream().anyMatch(EditorDraft::hasDraft);
    }

    public static CompletableFuture<Boolean> saveAll(Component root) {
        return onEdt(() -> {
            List<EditorDraft> drafts = collect(root).stream().filter(EditorDraft::hasDraft).toList();
            CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
            for (EditorDraft draft : drafts) {
                chain = chain.thenCompose(success -> onEdt(() -> {
                    if (!success || !collect(root).contains(draft)) return CompletableFuture.completedFuture(false);
                    if (!draft.hasDraft()) return CompletableFuture.completedFuture(true);
                    try {
                        return draft.saveDraft().handle((saved, failure) -> failure == null && Boolean.TRUE.equals(saved));
                    } catch (RuntimeException failure) {
                        return CompletableFuture.completedFuture(false);
                    }
                }));
            }
            return chain.thenCompose(success -> onEdt(() -> CompletableFuture.completedFuture(success && !hasDrafts(root))));
        });
    }

    public static CompletableFuture<Consent> confirmClose(Component scope, Component dialogOwner) {
        return onEdt(() -> {
            Consent consent = new Consent(scope);
            if (!hasDrafts(scope)) return CompletableFuture.completedFuture(consent);
            Object[] options = {LangUtil.i18n("draft.apply"), LangUtil.i18n("draft.discard"), LangUtil.i18n("draft.cancel")};
            int choice = JOptionPane.showOptionDialog(dialogOwner, LangUtil.i18n("draft.closePrompt"),
                    LangUtil.i18n("draft.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[0]);
            if (choice == 1) return CompletableFuture.completedFuture(consent.isValid() ? consent : null);
            if (choice != 0 || !consent.isValid()) return CompletableFuture.completedFuture(null);
            return saveAll(scope).thenCompose(saved -> onEdt(() -> CompletableFuture.completedFuture(
                    Boolean.TRUE.equals(saved) && consent.isValid() ? consent : null)));
        });
    }

    public static Consent confirmDiscard(Component scope, Component dialogOwner) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Draft consent requires EDT");
        Consent consent = new Consent(scope);
        if (!hasDrafts(scope)) return consent;
        Object[] options = {LangUtil.i18n("draft.discard"), LangUtil.i18n("draft.cancel")};
        int choice = JOptionPane.showOptionDialog(dialogOwner, LangUtil.i18n("draft.discardPrompt"),
                LangUtil.i18n("draft.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        return choice == 0 && consent.isValid() ? consent : null;
    }

    public static final class Consent {
        private final Component root;
        private final Map<EditorDraft, Long> revisions = new IdentityHashMap<>();

        public Consent(Component root) {
            this.root = root;
            for (EditorDraft draft : collect(root)) if (draft.hasDraft()) revisions.put(draft, draft.draftRevision());
        }

        public boolean isValid() {
            for (EditorDraft draft : collect(root)) {
                if (draft.hasDraft() && !Long.valueOf(draft.draftRevision()).equals(revisions.get(draft))) return false;
            }
            return true;
        }
    }

    /** Marshal callbacks explicitly: completed task futures may call on a worker. */
    public static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    public static <T> CompletableFuture<T> onEdt(Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        onEdt(() -> {
            if (result.isCancelled()) return;
            try {
                CompletableFuture<T> operation = action.get();
                result.whenComplete((value, failure) -> {
                    if (result.isCancelled()) operation.cancel(true);
                });
                operation.whenComplete((value, failure) -> {
                    if (failure == null) result.complete(value);
                    else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** Tracks the exact displayed/applied text without using undo history as dirty state. */
    public static final class TextState {
        private final JTextComponent text;
        private final Runnable changed;
        private String baseline;
        private long revision;
        private boolean replacing;

        public TextState(JTextComponent text, Runnable changed) {
            this.text = text;
            this.changed = changed;
            baseline = text.getText();
            text.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent event) { update(); }
                public void removeUpdate(DocumentEvent event) { update(); }
                public void changedUpdate(DocumentEvent event) { update(); }
                private void update() {
                    revision++;
                    if (!replacing) changed.run();
                }
            });
        }

        public boolean isDirty() { return !baseline.equals(text.getText()); }
        public long revision() { return revision; }

        public void applied(String appliedText) {
            baseline = appliedText;
            changed.run();
        }

        public void replaceClean(String content) {
            if (isDirty()) return;
            int dot = text.getCaret().getDot();
            int mark = text.getCaret().getMark();
            replacing = true;
            try {
                text.setText(content);
                baseline = content;
                int length = text.getDocument().getLength();
                text.setCaretPosition(Math.min(mark, length));
                text.moveCaretPosition(Math.min(dot, length));
            } finally {
                replacing = false;
            }
            changed.run();
        }
    }
}
