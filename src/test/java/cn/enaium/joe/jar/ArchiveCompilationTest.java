package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.compiler.CompileError;
import cn.enaium.joe.util.compiler.Compiler;
import cn.enaium.joe.util.compiler.virtual.MemoryClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldNode;

import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveCompilationTest {
    private static final String ORIGINAL = "public class Fixture { public static int value() { return 1; } }";
    private static final String EDITED = "public class Fixture { public static int value() { return 2; } }";

    @Test
    void laterTreeEditsAreNotOverwrittenByAnOlderCompilation() throws Exception {
        Jar archive = archive(Map.of("Fixture", ORIGINAL));
        ClassNode wrapper = archive.getClassNode("Fixture");
        ArchiveCompilation prepared = ArchiveCompilation.compile(ArchiveSnapshot.capture(archive), Map.of("Fixture", EDITED));
        assertEquals(1, value(archive));
        wrapper.getClassNode().fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "newer", "I", null, 7));
        assertThrows(ConcurrentModificationException.class, () -> prepared.applyTo(archive));
        assertSame(wrapper, archive.getClassNode("Fixture"));
        Class<?> current = load(archive, "Fixture");
        assertEquals(1, current.getMethod("value").invoke(null));
        assertEquals(7, current.getField("newer").get(null));
    }

    @Test
    void aResourceChangeAlsoPreventsStalePublication() throws Exception {
        Jar archive = archive(Map.of("Fixture", ORIGINAL));
        archive.putResource("payload", new byte[]{1, 2});
        ArchiveCompilation prepared = ArchiveCompilation.compile(ArchiveSnapshot.capture(archive), Map.of("Fixture", EDITED));
        archive.getResource("payload")[0] = 9;
        assertThrows(ConcurrentModificationException.class, () -> prepared.applyTo(archive));
        assertArrayEquals(new byte[]{9, 2}, archive.getResource("payload"));
        assertEquals(1, value(archive));
    }

    @Test
    void commitsAllOutputsRetainsWrappersAndPreservesIndependentDollarNamedTypes() throws Exception {
        Jar archive = archive(Map.of("Fixture", """
                public class Fixture {
                    public static int value() { return 1; }
                    public static class Removed {}
                    public static Runnable factory() { return new Runnable() { public void run() {} }; }
                }
                """, "Fixture$Independent", "public class Fixture$Independent { public static int value() { return 91; } }"));
        String anonymous = load(archive, "Fixture").getMethod("factory").invoke(null).getClass().getName();
        ClassNode original = archive.getClassNode("Fixture");
        ClassNode independent = archive.getClassNode("Fixture$Independent");
        byte[] resource = new byte[]{1, 2, 3};
        archive.putResource("payload", resource);
        ArchiveCompilation prepared = ArchiveCompilation.compile(ArchiveSnapshot.capture(archive), Map.of("Fixture", """
                public class Fixture {
                    public static int value() { return 2; }
                    public static class Added {}
                }
                """));
        assertTrue(archive.hasClass("Fixture$Removed"));
        ArchiveCompilation.Change changed = prepared.applyTo(archive);
        assertTrue(changed.changed());
        assertTrue(changed.generatedClasses().contains("Fixture$Added"));
        assertTrue(changed.removedClasses().contains("Fixture$Removed"));
        assertTrue(changed.removedClasses().contains(anonymous));
        assertFalse(archive.hasClass("Fixture$Removed"));
        assertFalse(archive.hasClass(anonymous));
        assertSame(original, archive.getClassNode("Fixture"));
        assertSame(independent, archive.getClassNode("Fixture$Independent"));
        assertSame(resource, archive.getResource("payload"));
        assertEquals(2, value(archive));
        assertEquals(91, load(archive, "Fixture$Independent").getMethod("value").invoke(null));
    }

    @Test
    void successfulResultsCannotBeReplayedAndNoOpDoesNotReplaceModels() throws Exception {
        Jar archive = archive(Map.of("Fixture", ORIGINAL));
        ClassNode wrapper = archive.getClassNode("Fixture");
        ArchiveCompilation prepared = ArchiveCompilation.compile(ArchiveSnapshot.capture(archive), Map.of("Fixture", ORIGINAL));
        assertFalse(prepared.applyTo(archive).changed());
        assertSame(wrapper, archive.getClassNode("Fixture"));
        assertThrows(IllegalStateException.class, () -> prepared.applyTo(archive));
        assertEquals(1, value(archive));
    }

    @Test
    void missingRequestedTypeCannotInsertDifferentTypesOrDeleteTheOriginal() throws Exception {
        Jar archive = archive(Map.of("Fixture", ORIGINAL));
        byte[] before = archive.getClassNode("Fixture").getClassBytes().clone();
        assertThrows(CompileError.class, () -> ArchiveCompilation.compile(ArchiveSnapshot.capture(archive),
                Map.of("Fixture", "class Different { public int value() { return 2; } }")));
        assertArrayEquals(before, archive.getClassNode("Fixture").getClassBytes());
        assertFalse(archive.hasClass("Different"));
        assertEquals(1, value(archive));
    }

    @Test
    void persistentCancellationSurvivesAClearedInterruptAndDoesNotConsumeTheResult() throws Exception {
        Jar archive = archive(Map.of("Fixture", ORIGINAL));
        ArchiveCompilation prepared = ArchiveCompilation.compile(ArchiveSnapshot.capture(archive), Map.of("Fixture", EDITED));
        AtomicBoolean cancelled = new AtomicBoolean(true);
        try {
            Thread.currentThread().interrupt();
            assertTrue(Thread.interrupted());
            assertThrows(CancellationException.class, () -> prepared.applyTo(archive, cancelled::get));
            assertEquals(1, value(archive));
            cancelled.set(false);
            assertTrue(prepared.applyTo(archive, cancelled::get).changed());
            assertEquals(2, value(archive));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void concurrentPreparedEditsHaveOnlyOneWinner() throws Exception {
        Jar archive = archive(Map.of("Fixture", ORIGINAL));
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(archive);
        ArchiveCompilation first = ArchiveCompilation.compile(baseline, Map.of("Fixture", EDITED));
        ArchiveCompilation second = ArchiveCompilation.compile(baseline,
                Map.of("Fixture", "public class Fixture { public static int value() { return 3; } }"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> commit(first, archive, barrier));
            var secondResult = executor.submit(() -> commit(second, archive, barrier));
            int wins = (firstResult.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (secondResult.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, wins);
        }
        int value = value(archive);
        assertTrue(value == 2 || value == 3);
    }

    @Test
    void cyclicEnclosingMetadataCannotDeleteUnrelatedClasses() throws Exception {
        Jar archive = archive(Map.of("Fixture", ORIGINAL, "First", "public class First {}", "Second", "public class Second {}"));
        archive.getClassNode("First").getClassNode().outerClass = "Second";
        archive.getClassNode("Second").getClassNode().outerClass = "First";
        ClassNode first = archive.getClassNode("First");
        ClassNode second = archive.getClassNode("Second");
        ArchiveCompilation prepared = ArchiveCompilation.compile(ArchiveSnapshot.capture(archive), Map.of("Fixture", EDITED));
        assertTrue(prepared.applyTo(archive).removedClasses().isEmpty());
        assertSame(first, archive.getClassNode("First"));
        assertSame(second, archive.getClassNode("Second"));
        assertEquals(2, value(archive));
    }

    private static boolean commit(ArchiveCompilation change, Jar archive, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            change.applyTo(archive);
            return true;
        } catch (ConcurrentModificationException conflict) {
            return false;
        }
    }

    private static Jar archive(Map<String, String> sources) throws CompileError {
        Jar archive = new Jar();
        Compiler.compileSources(sources, Map.of()).values().forEach(bytes -> archive.putClassNode(ClassNode.of(bytes)));
        return archive;
    }

    private static Class<?> load(Jar archive, String name) throws ClassNotFoundException {
        Map<String, byte[]> bytecode = new LinkedHashMap<>();
        for (ClassNode node : archive.getClasses()) bytecode.put(node.getCanonicalName(), node.getClassBytes());
        return new MemoryClassLoader(ArchiveCompilationTest.class.getClassLoader(), bytecode).loadClass(name);
    }

    private static int value(Jar archive) throws Exception {
        return (Integer) load(archive, "Fixture").getMethod("value").invoke(null);
    }
}
