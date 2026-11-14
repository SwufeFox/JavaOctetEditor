package cn.enaium.joe.gui.editor;

import cn.enaium.joe.jar.ArchiveSnapshot;
import cn.enaium.joe.jar.Jar;

/** Model-owner-thread document baseline and optimistic operation authorization. */
public final class ArchiveDocumentState {
    private Jar archive;
    private ArchiveSnapshot baseline;
    private long revision;
    private boolean dirty;

    public void replace(Jar archive) {
        this.archive = archive;
        baseline = archive == null ? null : ArchiveSnapshot.capture(archive);
        revision++;
        dirty = false;
    }

    public void changed() {
        revision++;
        refreshDirty();
    }

    public void refreshDirty() {
        dirty = archive != null && (baseline == null || !baseline.matches(archive));
    }

    public boolean isDirty() {
        return dirty;
    }

    /** A successful export establishes the bytes on disk, even if newer edits exist. */
    public boolean exported(Jar source, ArchiveSnapshot written) {
        if (archive != source) return false;
        baseline = written;
        refreshDirty();
        return true;
    }

    public Guard captureGuard() {
        return new Guard(archive, archive == null ? null : ArchiveSnapshot.capture(archive), revision);
    }

    public boolean isCurrent(Guard guard) {
        return guard.archive == archive && guard.revision == revision
                && (archive == null || guard.content.matches(archive));
    }

    public record Guard(Jar archive, ArchiveSnapshot content, long revision) {
    }
}
