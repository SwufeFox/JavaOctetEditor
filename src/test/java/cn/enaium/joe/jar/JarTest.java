package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ConcurrentModificationException;

import static org.junit.jupiter.api.Assertions.*;

class JarTest {
    @Test
    void collidingRenamesPreserveBothClassesAndTheOldIndex() {
        Jar archive = new Jar();
        ClassNode first = node("First");
        ClassNode second = node("Second");
        archive.putClassNode(first);
        archive.putClassNode(second);
        long revision = archive.getStructuralRevision();
        second.getClassNode().name = "First";

        IllegalStateException failure = assertThrows(IllegalStateException.class, archive::refresh);
        assertTrue(failure.getMessage().contains("First"));
        assertEquals(2, archive.getClassSize());
        assertSame(first, archive.getClassNode("First"));
        assertSame(second, archive.getClassNode("Second"));
        assertEquals(2, archive.copy().getClassSize());
        assertEquals(revision, archive.getStructuralRevision());

        second.getClassNode().name = "Third";
        archive.refresh();
        assertEquals(2, archive.getClassSize());
        assertFalse(archive.hasClass("Second"));
        assertSame(second, archive.getClassNode("Third"));
        assertNotEquals(revision, archive.getStructuralRevision());
    }

    @Test
    void existingViewsFollowSuccessfulRefreshWithoutAllowingIndexMutation() {
        Jar archive = new Jar();
        ClassNode node = node("Before");
        archive.putClassNode(node);
        var classes = archive.getClasses();
        node.getClassNode().name = "After";
        archive.refresh();
        assertEquals(1, classes.size());
        assertSame(node, classes.iterator().next());
        assertFalse(archive.hasClass("Before"));
        assertSame(node, archive.getClassNode("After"));
        assertThrows(UnsupportedOperationException.class, classes::clear);
        assertThrows(UnsupportedOperationException.class, () -> archive.getResources().put("x", new byte[0]));
    }

    @Test
    void replacementsAndExplicitRemovalsUseTheInternalNameIndex() {
        Jar archive = new Jar();
        archive.putClassNode(node("Example"));
        ClassNode replacement = node("Example");
        archive.putClassNode(replacement);
        assertEquals(1, archive.getClassSize());
        assertSame(replacement, archive.removeClass("Example"));
        assertNull(archive.removeClass("Example"));
        assertEquals(0, archive.getClassSize());

        byte[] resource = { 1, 2 };
        archive.putResource("resource", resource);
        assertSame(resource, archive.removeResource("resource"));
        assertNull(archive.removeResource("resource"));
    }

    @Test
    void copiesContainIndependentClassesAndResources() {
        Jar archive = new Jar();
        archive.putClassNode(node("Example"));
        archive.putResource("resource", new byte[] { 1, 2 });
        Jar copy = archive.copy();
        copy.getClassNode("Example").getClassNode().name = "Changed";
        copy.getResource("resource")[0] = 9;
        assertEquals("Example", archive.getClassNode("Example").getInternalName());
        assertArrayEquals(new byte[] { 1, 2 }, archive.getResource("resource"));
    }

    @Test
    void nullResourcesAreRejectedBeforeBulkMutation() {
        Jar archive = new Jar();
        assertThrows(NullPointerException.class, () -> archive.putResource("bad", null));
        Map<String, byte[]> resources = new LinkedHashMap<>();
        resources.put("valid", new byte[0]);
        resources.put("invalid", null);
        assertThrows(NullPointerException.class, () -> archive.putAllResource(resources));
        assertEquals(0, archive.getResourceSize());
    }

    @Test
    void structuralRevisionTracksPathsAndWrapperIdentityRatherThanContent() {
        Jar archive = new Jar();
        long emptyRevision = archive.getStructuralRevision();
        ClassNode original = node("Example");
        archive.putClassNode(original);
        long classRevision = archive.getStructuralRevision();
        assertNotEquals(emptyRevision, classRevision);
        archive.putClassNode(original);
        archive.refresh();
        assertEquals(classRevision, archive.getStructuralRevision());

        ClassNode replacement = node("Example");
        archive.putClassNode(replacement);
        long replacementRevision = archive.getStructuralRevision();
        assertNotEquals(classRevision, replacementRevision);
        assertSame(replacement, archive.getClassNode("Example"));
        assertNull(archive.removeClass("missing"));
        assertEquals(replacementRevision, archive.getStructuralRevision());
        archive.removeClass("Example");
        assertNotEquals(replacementRevision, archive.getStructuralRevision());

        long beforeResource = archive.getStructuralRevision();
        archive.putResource("first", new byte[]{1});
        long resourceRevision = archive.getStructuralRevision();
        assertNotEquals(beforeResource, resourceRevision);
        archive.putResource("first", new byte[]{2});
        archive.putAllResource(Map.of("first", new byte[]{3}));
        assertNull(archive.removeResource("missing"));
        assertEquals(resourceRevision, archive.getStructuralRevision());
        archive.putAllResource(Map.of("first", new byte[]{4}, "second", new byte[]{5}));
        long bulkRevision = archive.getStructuralRevision();
        assertNotEquals(resourceRevision, bulkRevision);
        archive.removeResource("first");
        assertNotEquals(bulkRevision, archive.getStructuralRevision());
    }

    @Test
    void unchangedStructureStillRequiresContentConflictChecks() {
        Jar archive = new Jar();
        ClassNode original = node("Example");
        archive.putClassNode(original);
        archive.putResource("payload", new byte[]{1});
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(archive);
        long revision = archive.getStructuralRevision();

        original.getClassNode().access |= Opcodes.ACC_FINAL;
        archive.refresh();
        assertEquals(revision, archive.getStructuralRevision());
        assertFalse(baseline.matches(archive));
        assertThrows(ConcurrentModificationException.class, () -> baseline.requireUnchanged(archive));

        ArchiveSnapshot classEdit = ArchiveSnapshot.capture(archive);
        archive.putResource("payload", new byte[]{2});
        assertEquals(revision, archive.getStructuralRevision());
        assertFalse(classEdit.matches(archive));
        assertThrows(ConcurrentModificationException.class, () -> classEdit.requireUnchanged(archive));

        ArchiveSnapshot resourceEdit = ArchiveSnapshot.capture(archive);
        archive.getResource("payload")[0] = 3;
        assertEquals(revision, archive.getStructuralRevision());
        assertFalse(resourceEdit.matches(archive));
    }

    @Test
    void rejectedMutationsDoNotAdvanceStructuralRevision() {
        Jar archive = new Jar();
        ClassNode original = node("Example");
        archive.putClassNode(original);
        archive.putResource("existing", new byte[]{1});
        long revision = archive.getStructuralRevision();
        Map<String, byte[]> invalid = new LinkedHashMap<>();
        invalid.put("new", new byte[]{2});
        invalid.put("invalid", null);

        assertThrows(NullPointerException.class, () -> archive.putAllResource(invalid));
        assertThrows(NullPointerException.class, () -> archive.putResource("new", null));
        assertThrows(NullPointerException.class, () -> archive.putClassNode(null));
        original.getClassNode().name = null;
        assertThrows(NullPointerException.class, archive::refresh);
        assertEquals(revision, archive.getStructuralRevision());
        assertSame(original, archive.getClassNode("Example"));
        assertNull(archive.getResource("new"));
        original.getClassNode().name = "Example";
        archive.refresh();
        assertEquals(revision, archive.getStructuralRevision());
    }

    private static ClassNode node(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return ClassNode.of(writer.toByteArray());
    }
}
