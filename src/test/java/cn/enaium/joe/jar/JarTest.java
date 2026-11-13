package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JarTest {
    @Test
    void collidingRenamesPreserveBothClassesAndTheOldIndex() {
        Jar archive = new Jar();
        ClassNode first = node("First");
        ClassNode second = node("Second");
        archive.putClassNode(first);
        archive.putClassNode(second);
        second.getClassNode().name = "First";

        IllegalStateException failure = assertThrows(IllegalStateException.class, archive::refresh);
        assertTrue(failure.getMessage().contains("First"));
        assertEquals(2, archive.getClassSize());
        assertSame(first, archive.getClassNode("First"));
        assertSame(second, archive.getClassNode("Second"));
        assertEquals(2, archive.copy().getClassSize());

        second.getClassNode().name = "Third";
        archive.refresh();
        assertEquals(2, archive.getClassSize());
        assertFalse(archive.hasClass("Second"));
        assertSame(second, archive.getClassNode("Third"));
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

    private static ClassNode node(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return ClassNode.of(writer.toByteArray());
    }
}
