package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ASMClassLoader;
import cn.enaium.joe.util.classes.ClassNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveIOTest {
    @TempDir
    Path directory;

    @Test
    void classesEditsAndResourcesRoundTripWithoutDesktopInitialization() throws Exception {
        Jar archive = new Jar();
        ClassNode edited = ClassNode.of(classBytes("pkg/Example", 1));
        var instructions = edited.getClassNode().methods.getFirst().instructions;
        instructions.clear();
        instructions.add(new LdcInsnNode(9));
        instructions.add(new InsnNode(Opcodes.IRETURN));
        archive.putClassNode(edited);
        archive.putClassNode(ClassNode.of(classBytes("DefaultPackage", 2)));
        archive.putResource("assets/data.bin", new byte[] { 0, 1, -1, 4 });
        archive.putResource("empty.bin", new byte[0]);
        archive.putResource("empty/", new byte[0]);
        archive.putResource("windows\\path.txt", new byte[] { 5 });
        Path output = directory.resolve("round-trip.jar");
        List<Integer> outputProgress = new ArrayList<>();
        ArchiveIO.write(archive, output, outputProgress::add);
        List<Integer> inputProgress = new ArrayList<>();
        Jar reopened = ArchiveIO.read(output, inputProgress::add);

        assertEquals(2, reopened.getClassSize());
        assertEquals(9, value(reopened.getClassNode("pkg/Example").getClassBytes()));
        assertEquals("", reopened.getClassNode("DefaultPackage").getCanonicalPackageName());
        assertArrayEquals(new byte[] { 0, 1, -1, 4 }, reopened.getResource("assets/data.bin"));
        assertArrayEquals(new byte[0], reopened.getResource("empty.bin"));
        assertArrayEquals(new byte[0], reopened.getResource("empty/"));
        assertArrayEquals(new byte[] { 5 }, reopened.getResource("windows/path.txt"));
        assertCompleted(outputProgress);
        assertCompleted(inputProgress);
    }

    @Test
    void versionedClassesPreserveExactPathsAndBytesAlongsideTheBaseClass() throws Exception {
        byte[] base = classBytes("pkg/Example", 1);
        byte[] java11 = classBytes("pkg/Example", 11);
        byte[] java17 = classBytes("pkg/Example", 17);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("pkg/Example.class", base);
        entries.put("META-INF/versions/11/pkg/Example.class", java11);
        entries.put("META-INF/versions/17/pkg/Example.class", java17);
        Path input = zip("multi-release.jar", entries);
        Jar archive = ArchiveIO.read(input);
        assertEquals(1, archive.getClassSize());
        assertEquals(1, value(archive.getClassNode("pkg/Example").getClassBytes()));
        assertArrayEquals(java11, archive.getResource("META-INF/versions/11/pkg/Example.class"));
        assertArrayEquals(java17, archive.getResource("META-INF/versions/17/pkg/Example.class"));

        Path output = directory.resolve("multi-release-export.jar");
        ArchiveIO.write(archive, output);
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertArrayEquals(base, zip.getInputStream(zip.getEntry("pkg/Example.class")).readAllBytes());
            assertArrayEquals(java11, zip.getInputStream(zip.getEntry("META-INF/versions/11/pkg/Example.class")).readAllBytes());
            assertArrayEquals(java17, zip.getInputStream(zip.getEntry("META-INF/versions/17/pkg/Example.class")).readAllBytes());
        }
        assertEquals(1, ArchiveIO.read(output).getClassSize());
    }

    @Test
    void directoriesAndSingleClassesUseDeclaredClassNamesAndPortablePaths() throws Exception {
        Path input = Files.createDirectories(directory.resolve("classes"));
        Files.createDirectories(input.resolve("pkg"));
        Files.createDirectories(input.resolve("assets/nested"));
        Files.createDirectories(input.resolve("empty"));
        Files.write(input.resolve("pkg/Example.class"), classBytes("pkg/Example", 1));
        Files.write(input.resolve("DefaultPackage.class"), classBytes("DefaultPackage", 2));
        Files.writeString(input.resolve("assets/nested/data.txt"), "resource");
        Jar loaded = ArchiveIO.read(input);
        assertEquals(2, loaded.getClassSize());
        assertEquals("resource", new String(loaded.getResource("assets/nested/data.txt"), StandardCharsets.UTF_8));
        assertArrayEquals(new byte[0], loaded.getResource("empty/"));
        assertTrue(loaded.getResources().keySet().stream().noneMatch(name -> name.contains("\\")));
        Path output = directory.resolve("directory.jar");
        ArchiveIO.write(loaded, output);
        assertEquals(2, ArchiveIO.read(output).getClassSize());

        Path single = directory.resolve("arbitrary-file-name.class");
        Files.write(single, classBytes("DeclaredName", 3));
        Jar oneClass = ArchiveIO.read(single);
        assertEquals(1, oneClass.getClassSize());
        assertNotNull(oneClass.getClassNode("DeclaredName"));
        Files.delete(single);
    }

    @Test
    void emptyArchivesAndDirectoriesReachOneHundredPercent() throws Exception {
        Path output = directory.resolve("empty.jar");
        List<Integer> progress = new ArrayList<>();
        ArchiveIO.write(new Jar(), output, progress::add);
        assertCompleted(progress);
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertEquals(0, zip.size());
        }
        progress.clear();
        Jar empty = ArchiveIO.read(output, progress::add);
        assertEquals(0, empty.getClassSize());
        assertEquals(0, empty.getResourceSize());
        assertCompleted(progress);
        progress.clear();
        ArchiveIO.read(Files.createDirectory(directory.resolve("empty-directory")), progress::add);
        assertCompleted(progress);
    }

    @Test
    void missingAndCorruptInputsFailWithoutReturningPartialModels() throws Exception {
        assertThrows(IOException.class, () -> ArchiveIO.read(directory.resolve("missing.jar")));
        Path invalidZip = directory.resolve("invalid.jar");
        Files.writeString(invalidZip, "this is not a zip");
        assertThrows(IOException.class, () -> ArchiveIO.read(invalidZip));
        Path invalidClass = directory.resolve("invalid.class");
        Files.write(invalidClass, new byte[] { 1, 2, 3 });
        assertThrows(IOException.class, () -> ArchiveIO.read(invalidClass));
        Path truncatedClass = directory.resolve("truncated.class");
        Files.write(truncatedClass, new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });
        assertThrows(IOException.class, () -> ArchiveIO.read(truncatedClass));

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("valid.txt", new byte[] { 1 });
        entries.put("Broken.class", new byte[] { 0, 1 });
        Path invalidEntry = zip("invalid-entry.jar", entries);
        assertThrows(IOException.class, () -> ArchiveIO.read(invalidEntry));
        Files.delete(invalidEntry);
        Path misplaced = zip("misplaced.jar", Map.of("Alias.class", classBytes("Actual", 1)));
        assertThrows(IOException.class, () -> ArchiveIO.read(misplaced));

        Path invalidDirectory = Files.createDirectory(directory.resolve("bad-directory"));
        Files.write(invalidDirectory.resolve("Broken.class"), new byte[] { 1 });
        assertThrows(IOException.class, () -> ArchiveIO.read(invalidDirectory));
    }

    @Test
    void corruptStoredZipPayloadIsDetectedByItsCrc() throws Exception {
        Path input = directory.resolve("bad-crc.jar");
        byte[] payload = { 10, 20, 30, 40 };
        CRC32 crc = new CRC32();
        crc.update(payload);
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(input))) {
            ZipEntry entry = new ZipEntry("resource.bin");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            stream.putNextEntry(entry);
            stream.write(payload);
            stream.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(input);
        int nameLength = (bytes[26] & 255) | ((bytes[27] & 255) << 8);
        int extraLength = (bytes[28] & 255) | ((bytes[29] & 255) << 8);
        bytes[30 + nameLength + extraLength] ^= 1;
        Files.write(input, bytes);
        assertThrows(IOException.class, () -> ArchiveIO.read(input));
    }

    @Test
    void backslashArchiveEntriesAreNormalizedBeforeClassAndResourceIndexing() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg\\Example.class", classBytes("pkg/Example", 1));
        entries.put("assets\\nested\\data", new byte[] { 3 });
        entries.put("empty\\", new byte[0]);
        Jar archive = ArchiveIO.read(zip("backslashes.jar", entries));
        assertNotNull(archive.getClassNode("pkg/Example"));
        assertArrayEquals(new byte[] { 3 }, archive.getResource("assets/nested/data"));
        assertArrayEquals(new byte[0], archive.getResource("empty/"));
    }

    @Test
    void exactDuplicateZipNamesAreRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("one.txt", new byte[] { 1 });
        entries.put("two.txt", new byte[] { 2 });
        Path input = zip("exact-duplicates.jar", entries);
        byte[] bytes = Files.readAllBytes(input);
        byte[] before = "two.txt".getBytes(StandardCharsets.UTF_8);
        byte[] after = "one.txt".getBytes(StandardCharsets.UTF_8);
        // Replace the local and central names without changing entry sizes or CRCs.
        int replacements = 0;
        for (int offset = 0; offset <= bytes.length - before.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < before.length; index++) {
                if (bytes[offset + index] != before[index]) matches = false;
            }
            if (matches) {
                System.arraycopy(after, 0, bytes, offset, after.length);
                replacements++;
            }
        }
        assertEquals(2, replacements);
        Files.write(input, bytes);
        assertThrows(IOException.class, () -> ArchiveIO.read(input));
    }

    @Test
    void duplicateNormalizedNamesAndFileDirectoryCollisionsAreRejected() throws Exception {
        Map<String, byte[]> duplicate = new LinkedHashMap<>();
        duplicate.put("path\\file.txt", new byte[] { 1 });
        duplicate.put("path/file.txt", new byte[] { 2 });
        assertThrows(IOException.class, () -> ArchiveIO.read(zip("duplicate.jar", duplicate)));
        Map<String, byte[]> conflict = new LinkedHashMap<>();
        conflict.put("path", new byte[0]);
        conflict.put("path/file.txt", new byte[0]);
        assertThrows(IOException.class, () -> ArchiveIO.read(zip("conflict.jar", conflict)));
        Map<String, byte[]> reverseConflict = new LinkedHashMap<>();
        reverseConflict.put("path/file.txt", new byte[0]);
        reverseConflict.put("path", new byte[0]);
        assertThrows(IOException.class, () -> ArchiveIO.read(zip("reverse-conflict.jar", reverseConflict)));
        assertThrows(IOException.class, () -> ArchiveIO.read(zip("unsafe.jar", Map.of("../outside.txt", new byte[0]))));
    }

    @Test
    void invalidModelsCannotReplaceAnExistingOutput() throws Exception {
        Path output = directory.resolve("existing.jar");
        byte[] original = "existing output".getBytes(StandardCharsets.UTF_8);
        Files.write(output, original);
        Jar collision = new Jar();
        collision.putClassNode(ClassNode.of(classBytes("Example", 1)));
        collision.putResource("Example.class", new byte[0]);
        assertThrows(IOException.class, () -> ArchiveIO.write(collision, output));
        assertArrayEquals(original, Files.readAllBytes(output));

        Jar normalizedCollision = new Jar();
        normalizedCollision.putResource("a\\b", new byte[0]);
        normalizedCollision.putResource("a/b", new byte[0]);
        assertThrows(IOException.class, () -> ArchiveIO.write(normalizedCollision, output));
        assertArrayEquals(original, Files.readAllBytes(output));

        Jar renamedCollision = new Jar();
        ClassNode first = ClassNode.of(classBytes("First", 1));
        ClassNode second = ClassNode.of(classBytes("Second", 2));
        renamedCollision.putClassNode(first);
        renamedCollision.putClassNode(second);
        second.getClassNode().name = "First";
        assertThrows(IOException.class, () -> ArchiveIO.write(renamedCollision, output));
        assertArrayEquals(original, Files.readAllBytes(output));

        Jar invalidTree = new Jar();
        ClassNode invalid = ClassNode.of(classBytes("Invalid", 1));
        invalid.getClassNode().methods = null;
        invalidTree.putClassNode(invalid);
        assertThrows(IOException.class, () -> ArchiveIO.write(invalidTree, output));
        assertArrayEquals(original, Files.readAllBytes(output));
        assertOnlyOutputRemains(output);
    }

    @Test
    void interruptedExportPreservesExistingOutputAndCleansTemporaryFile() throws Exception {
        Path output = directory.resolve("existing.jar");
        byte[] original = { 4, 3, 2, 1 };
        Files.write(output, original);
        Jar archive = new Jar();
        archive.putResource("first", new byte[] { 1 });
        archive.putResource("second", new byte[] { 2 });
        try {
            assertThrows(InterruptedIOException.class, () -> ArchiveIO.write(archive, output, progress -> {
                if (progress > 0) Thread.currentThread().interrupt();
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertArrayEquals(original, Files.readAllBytes(output));
        assertOnlyOutputRemains(output);
    }

    @Test
    void progressObserverFailureCannotReplaceExistingOutputEvenAtFinalNotification() throws Exception {
        Path output = directory.resolve("existing.jar");
        byte[] original = { 1, 2, 3 };
        Files.write(output, original);
        assertThrows(IllegalStateException.class, () -> ArchiveIO.write(new Jar(), output, progress -> {
            if (progress == 100) throw new IllegalStateException("observer failed");
        }));
        assertArrayEquals(original, Files.readAllBytes(output));
        assertOnlyOutputRemains(output);
    }

    @Test
    void interruptedReadPropagatesWithoutClearingTheInterruptFlag() throws Exception {
        Path input = zip("interrupted.jar", Map.of("resource", new byte[] { 1 }));
        try {
            assertThrows(InterruptedIOException.class, () -> ArchiveIO.read(input, progress -> {
                if (progress > 0) Thread.currentThread().interrupt();
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        Files.delete(input);
    }

    @Test
    void nonReplacingExportPreservesExistingAndConcurrentlyCreatedTargets() throws Exception {
        Path output = directory.resolve("existing.jar");
        Files.writeString(output, "existing");
        assertThrows(FileAlreadyExistsException.class, () -> ArchiveIO.write(new Jar(), output, false));
        assertEquals("existing", Files.readString(output));
        Files.delete(output);
        assertThrows(FileAlreadyExistsException.class, () -> ArchiveIO.write(new Jar(), output, false, progress -> {
            if (progress == 100) {
                try {
                    Files.writeString(output, "created before commit");
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }));
        assertEquals("created before commit", Files.readString(output));
        assertOnlyOutputRemains(output);
    }

    @Test
    void replacingTheLoadedInputIsSafeAndProducesAReopenableArchive() throws Exception {
        Path input = zip("same-path.jar", Map.of("resource", new byte[] { 1 }));
        Jar archive = ArchiveIO.read(input);
        archive.putResource("resource", new byte[] { 2 });
        archive.putClassNode(ClassNode.of(classBytes("Added", 3)));
        ArchiveIO.write(archive, input);
        Jar reopened = ArchiveIO.read(input);
        assertArrayEquals(new byte[] { 2 }, reopened.getResource("resource"));
        assertEquals(3, value(reopened.getClassNode("Added").getClassBytes()));
    }

    @Test
    void snapshotExportRetainsCapturedBytesAndCancellationPreservesExistingOutput() throws Exception {
        Jar archive = new Jar();
        byte[] originalClass = classBytes("pkg/Example", 1);
        byte[] resource = { 0, 1, -1, 4 };
        archive.putClassNode(ClassNode.of(originalClass));
        archive.putResource("data.bin", resource);
        ArchiveSnapshot captured = ArchiveSnapshot.capture(archive);
        archive.putClassNode(ClassNode.of(classBytes("pkg/Example", 2)));
        resource[0] = 9;
        Path output = directory.resolve("snapshot.jar");
        List<Integer> progress = new ArrayList<>();

        ArchiveIO.write(captured, output, false, progress::add);

        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertArrayEquals(originalClass, zip.getInputStream(zip.getEntry("pkg/Example.class")).readAllBytes());
            assertArrayEquals(new byte[] { 0, 1, -1, 4 }, zip.getInputStream(zip.getEntry("data.bin")).readAllBytes());
        }
        assertCompleted(progress);
        byte[] exported = Files.readAllBytes(output);
        ArchiveSnapshot changed = ArchiveSnapshot.capture(archive);
        assertThrows(java.util.concurrent.CancellationException.class, () -> ArchiveIO.write(changed, output, true, value -> {
            if (value == 100) throw new java.util.concurrent.CancellationException("Cancelled before replacement");
        }));
        assertArrayEquals(exported, Files.readAllBytes(output));
        assertOnlyOutputRemains(output);
    }

    private Path zip(String fileName, Map<String, byte[]> entries) throws IOException {
        Path path = directory.resolve(fileName);
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                stream.putNextEntry(new ZipEntry(entry.getKey()));
                stream.write(entry.getValue());
                stream.closeEntry();
            }
        }
        return path;
    }

    private void assertOnlyOutputRemains(Path output) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(List.of(output), files.toList());
        }
    }

    private static void assertCompleted(List<Integer> progress) {
        assertFalse(progress.isEmpty());
        assertEquals(0, progress.getFirst().intValue());
        assertEquals(100, progress.getLast().intValue());
        int previous = 0;
        for (int value : progress) {
            assertTrue(value >= previous && value <= 100);
            previous = value;
        }
    }

    private static int value(byte[] bytes) throws Exception {
        String name = new ClassReader(bytes).getClassName().replace('/', '.');
        return (int) new ASMClassLoader().defineClass(name, bytes).getMethod("value").invoke(null);
    }

    private static byte[] classBytes(String name, int value) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
