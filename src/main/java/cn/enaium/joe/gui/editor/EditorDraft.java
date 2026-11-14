package cn.enaium.joe.gui.editor;

import java.util.concurrent.CompletableFuture;

/** An editable leaf whose text has not yet been applied to the archive. */
public interface EditorDraft {
    boolean hasDraft();
    long draftRevision();
    String draftName();
    CompletableFuture<Boolean> saveDraft();
}
