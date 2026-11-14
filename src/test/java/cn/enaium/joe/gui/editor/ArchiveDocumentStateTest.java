package cn.enaium.joe.gui.editor;

import cn.enaium.joe.jar.ArchiveSnapshot;
import cn.enaium.joe.jar.Jar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveDocumentStateTest {
    @Test
    void exportOfCapturedBytesDoesNotCleanEditsMadeWhileWriting() {
        Jar archive = archive();
        ArchiveDocumentState state = new ArchiveDocumentState();
        state.replace(archive);
        archive.putResource("data.txt", new byte[]{2});
        state.changed();
        ArchiveSnapshot exported = ArchiveSnapshot.capture(archive);
        archive.putResource("data.txt", new byte[]{3});
        state.changed();

        assertTrue(state.exported(archive, exported));
        assertTrue(state.isDirty());
        archive.putResource("data.txt", new byte[]{2});
        state.changed();
        assertFalse(state.isDirty());
    }

    @Test
    void actualBaselineContentDeterminesDirtyStateIncludingReverts() {
        Jar archive = archive();
        ArchiveDocumentState state = new ArchiveDocumentState();
        state.replace(archive);
        state.changed();
        assertFalse(state.isDirty());
        archive.putResource("other.txt", archive.removeResource("data.txt"));
        state.changed();
        assertTrue(state.isDirty());
        archive.putResource("data.txt", archive.removeResource("other.txt"));
        state.changed();
        assertFalse(state.isDirty());
    }

    @Test
    void loadAuthorizationRejectsChangesEvenWhenLaterReverted() {
        Jar archive = archive();
        ArchiveDocumentState state = new ArchiveDocumentState();
        state.replace(archive);
        ArchiveDocumentState.Guard authorized = state.captureGuard();
        assertTrue(state.isCurrent(authorized));
        archive.putResource("data.txt", new byte[]{2});
        state.changed();
        archive.putResource("data.txt", new byte[]{1});
        state.changed();
        assertFalse(state.isDirty());
        assertFalse(state.isCurrent(authorized));
    }

    @Test
    void loadAuthorizationAlsoRejectsUnannouncedByteMutationsAndReplacement() {
        Jar archive = archive();
        ArchiveDocumentState state = new ArchiveDocumentState();
        state.replace(archive);
        ArchiveDocumentState.Guard authorized = state.captureGuard();
        archive.getResource("data.txt")[0] = 9;
        assertFalse(state.isCurrent(authorized));
        state.replace(archive());
        assertFalse(state.isCurrent(authorized));
    }

    @Test
    void finishingOldDocumentExportCannotChangeReplacementBaseline() {
        Jar old = archive();
        ArchiveDocumentState state = new ArchiveDocumentState();
        state.replace(old);
        ArchiveSnapshot exported = ArchiveSnapshot.capture(old);
        Jar replacement = archive();
        state.replace(replacement);
        replacement.putResource("data.txt", new byte[]{3});
        state.changed();
        assertFalse(state.exported(old, exported));
        assertTrue(state.isDirty());
    }

    private static Jar archive() {
        Jar archive = new Jar();
        archive.putResource("data.txt", new byte[]{1});
        return archive;
    }
}
