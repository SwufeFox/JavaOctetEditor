package cn.enaium.joe.util.task;

import cn.enaium.joe.jar.ArchiveIO;
import cn.enaium.joe.jar.ArchiveSnapshot;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.tasks.CompileSourceTask;
import cn.enaium.joe.util.task.tasks.CompileVisitorTask;
import cn.enaium.joe.util.task.tasks.OutputJarTask;
import cn.enaium.joe.util.task.tasks.TransformerTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveTaskTest {
    @TempDir Path directory;

    @Test
    void stagedTransformationRejectsLaterTreeAndResourceEditsWithoutPartialChanges() {
        Jar live = archive();
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(live);
        Jar staged = baseline.toJar();
        staged.removeClass("sample/Removed");
        staged.getClassNode("sample/Keep").getClassNode().sourceFile = "transformed.java";
        ArchiveChange change = new ArchiveChange(baseline, staged);
        live.getClassNode("sample/Keep").getClassNode().sourceFile = "newer.java";
        live.getResource("data.bin")[0] = 9;

        assertThrows(ConcurrentModificationException.class, () -> change.applyTo(live));
        assertTrue(live.hasClass("sample/Removed"));
        assertEquals("newer.java", live.getClassNode("sample/Keep").getNodeInternal().sourceFile);
        assertArrayEquals(new byte[] {9}, live.getResource("data.bin"));
    }

    @Test
    void stagedTransformationKeepsRetainedWrappersAndRemovesObsoleteNames() {
        Jar live = archive();
        ClassNode retained = live.getClassNode("sample/Keep");
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(live);
        Jar staged = baseline.toJar();
        staged.removeClass("sample/Removed");
        staged.putClassNode(node("sample/Renamed"));
        staged.getClassNode("sample/Keep").getClassNode().sourceFile = "changed.java";
        ArchiveChange change = new ArchiveChange(baseline, staged);
        // Script-owned data must no longer influence an already staged result.
        staged.getClassNode("sample/Keep").getClassNode().sourceFile = "too-late.java";

        change.applyTo(live);
        assertSame(retained, live.getClassNode("sample/Keep"));
        assertEquals("changed.java", retained.getNodeInternal().sourceFile);
        assertFalse(live.hasClass("sample/Removed"));
        assertTrue(live.hasClass("sample/Renamed"));
        assertThrows(IllegalStateException.class, () -> change.applyTo(live));
    }

    @Test
    void archiveExportUsesClassesAndResourcesCapturedBeforeDispatch() throws Exception {
        Jar live = archive();
        byte[] original = live.getClassNode("sample/Keep").getClassBytes().clone();
        Path output = directory.resolve("captured.jar");
        OutputJarTask task = new OutputJarTask(live, output.toFile());
        live.getClassNode("sample/Keep").getClassNode().sourceFile = "later.java";
        live.removeClass("sample/Removed");
        live.getResource("data.bin")[0] = 8;
        live.putClassNode(node("sample/Later"));

        assertTrue(task.get());
        Jar exported = ArchiveIO.read(output);
        assertArrayEquals(original, exported.getClassNode("sample/Keep").getClassBytes());
        assertTrue(exported.hasClass("sample/Removed"));
        assertFalse(exported.hasClass("sample/Later"));
        assertArrayEquals(new byte[] {1}, exported.getResource("data.bin"));
    }

    @Test
    void sourceTaskUsesSharedCompilationFamilyRemovalAndConflictGuards() {
        Jar live = archive();
        new CompileSourceTask("sample.Keep", "package sample; public class Keep { static class Obsolete {} }",
                ArchiveSnapshot.capture(live)).get().applyTo(live);
        ClassNode retained = live.getClassNode("sample/Keep");
        var compilation = new CompileSourceTask("sample.Keep", "package sample; public class Keep {}",
                ArchiveSnapshot.capture(live)).get();
        var change = compilation.applyTo(live);
        assertSame(retained, live.getClassNode("sample/Keep"));
        assertFalse(live.hasClass("sample/Keep$Obsolete"));
        assertTrue(change.removedClasses().contains("sample/Keep$Obsolete"));

        var older = new CompileSourceTask("sample.Keep", "package sample; public class Keep { public int value; }",
                ArchiveSnapshot.capture(live)).get();
        retained.getClassNode().sourceFile = "newer.java";
        assertThrows(ConcurrentModificationException.class, () -> older.applyTo(live));
        assertEquals(0, retained.getFields().length);
    }

    @Test
    void transformerReadsSubmittedBytesAndRejectsLaterLiveEdits() {
        Jar live = archive();
        Map<String, String> observedSources = new HashMap<>();
        TransformerTask task = new TransformerTask(live, (name, node) -> {
            observedSources.put(name, node.getNodeInternal().sourceFile);
            node.getClassNode().sourceFile = "transformed.java";
            return node;
        });
        live.getClassNode("sample/Keep").getClassNode().sourceFile = "newer.java";
        live.getResource("data.bin")[0] = 9;

        ArchiveChange change = task.get();

        assertEquals(2, observedSources.size());
        assertTrue(observedSources.containsKey("sample.Keep"));
        assertNull(observedSources.get("sample.Keep"));
        assertThrows(ConcurrentModificationException.class, () -> change.applyTo(live));
        assertEquals("newer.java", live.getClassNode("sample/Keep").getNodeInternal().sourceFile);
        assertArrayEquals(new byte[] {9}, live.getResource("data.bin"));
    }

    @Test
    void visitorTaskCanCompileAgainstEditorRuntimeDependencies() {
        String source = "import org.objectweb.asm.*; public class VisitorScript {"
                + "public static byte[] dump() { ClassWriter writer = new ClassWriter(0);"
                + "writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, \"sample/Generated\", null, \"java/lang/Object\", null);"
                + "writer.visitEnd(); return writer.toByteArray(); }}";
        ClassNode generated = new CompileVisitorTask("VisitorScript", source, Map.of()).get();
        assertEquals("sample/Generated", generated.getInternalName());
    }

    private static Jar archive() {
        Jar archive = new Jar();
        archive.putClassNode(node("sample/Keep"));
        archive.putClassNode(node("sample/Removed"));
        archive.putResource("data.bin", new byte[] {1});
        return archive;
    }

    private static ClassNode node(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return ClassNode.of(writer.toByteArray());
    }
}
