package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;

import java.util.ConcurrentModificationException;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveSnapshotTest {
    @Test
    void capturedContentSurvivesLiveTreeAndResourceMutations() {
        Jar live = archive();
        byte[] originalClass = live.getClassNode("Fixture").getClassBytes().clone();
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(live);
        var method = live.getClassNode("Fixture").getClassNode().methods.getFirst();
        method.instructions.set(method.instructions.getFirst(), new InsnNode(Opcodes.ICONST_2));
        live.getResource("payload.bin")[0] = 99;

        Jar captured = baseline.toJar();
        assertArrayEquals(originalClass, captured.getClassNode("Fixture").getClassBytes());
        assertArrayEquals(new byte[]{1, 2, 3}, captured.getResource("payload.bin"));
        assertFalse(baseline.matches(live));
        assertTrue(baseline.matches(captured));
    }

    @Test
    void mutableCopiesDoNotShareResourcesOrClassTreesWithTheBaseline() {
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(archive());
        Jar first = baseline.toJar();
        first.getResource("payload.bin")[1] = 77;
        first.getClassNode("Fixture").getClassNode().name = "Renamed";
        first.refresh();

        Jar second = baseline.toJar();
        assertTrue(second.hasClass("Fixture"));
        assertFalse(second.hasClass("Renamed"));
        assertArrayEquals(new byte[]{1, 2, 3}, second.getResource("payload.bin"));
        assertTrue(baseline.matches(second));
        assertFalse(baseline.matches(first));
    }

    @Test
    void resourceRenameWithTheSameEntryCountIsAConflict() {
        Jar live = archive();
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(live);
        live.putResource("other.bin", live.removeResource("payload.bin"));
        assertFalse(baseline.matches(live));
        assertThrows(ConcurrentModificationException.class, () -> baseline.requireUnchanged(live));
        assertArrayEquals(new byte[]{1, 2, 3}, live.getResource("other.bin"));
    }

    @Test
    void inconsistentRenamesCannotBeCapturedOrMistakenForUnchangedData() {
        Jar live = archive();
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(live);
        live.getClassNode("Fixture").getClassNode().name = "Renamed";
        assertFalse(baseline.matches(live));
        assertThrows(IllegalStateException.class, () -> ArchiveSnapshot.capture(live));
        assertEquals(1, live.getClassSize());
        live.refresh();
        Jar restored = ArchiveSnapshot.capture(live).toJar();
        assertTrue(restored.hasClass("Renamed"));
        assertFalse(restored.hasClass("Fixture"));
    }

    private static Jar archive() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "Fixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        Jar jar = new Jar();
        jar.putClassNode(ClassNode.of(writer.toByteArray()));
        jar.putResource("payload.bin", new byte[]{1, 2, 3});
        return jar;
    }
}
