package cn.enaium.joe.tool;

import com.google.gson.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveToolsTest {
    @TempDir
    Path directory;

    private final ArchiveTools tools = new ArchiveTools();

    @AfterEach
    void close() {
        tools.close();
    }

    @Test
    void separateHandlesDoNotShareEditsAndClosedHandlesCannotBeReused() throws Exception {
        Path input = fixture("shared.jar");
        String first = open(input);
        String second = open(input);
        call("write_resource", first, "path", "message.txt", "content", "changed", "expectedRevision", 0);
        assertEquals("changed", call("read_resource", first, "path", "message.txt").get("content").getAsString());
        assertEquals("original", call("read_resource", second, "path", "message.txt").get("content").getAsString());
        assertEquals("original", new String(entry(input, "message.txt"), StandardCharsets.UTF_8));
        call("close_archive", first);
        assertFailure("ARCHIVE_NOT_FOUND", () -> call("archive_info", first));
        assertEquals(0, call("archive_info", second).get("revision").getAsLong());
    }

    @Test
    void staleAndInvalidArgumentsCannotOverwriteResourceChanges() throws Exception {
        String id = open(fixture("revisions.jar"));
        JsonObject edited = call("write_resource", id, "path", "message.txt", "content", "first", "expectedRevision", 0);
        assertEquals(1, edited.get("revision").getAsLong());
        assertFailure("REVISION_CONFLICT", () -> call("write_resource", id,
                "path", "message.txt", "content", "stale", "expectedRevision", 0));
        assertFailure("INVALID_ARGUMENT", () -> call("write_resource", id,
                "path", "message.txt", "content", "invalid", "expectedRevision", 1.5));
        assertFailure("INVALID_ARGUMENT", () -> call("write_resource", id,
                "path", "../outside", "content", "invalid", "expectedRevision", 1));
        assertEquals("first", call("read_resource", id, "path", "message.txt").get("content").getAsString());
        assertEquals(1, call("archive_info", id).get("revision").getAsLong());
    }

    @Test
    void compilationIsTransactionalAndGeneratedInnerClassesRoundTrip() throws Exception {
        String id = open(fixture("compile.jar"));
        String originalHash = call("inspect_class", id, "name", "demo.Sample").get("sha256").getAsString();
        JsonObject invalid = arguments("sources", Map.of("demo.Sample", "package demo; public class Sample { broken }"),
                "expectedRevision", 0);
        assertFailure("COMPILATION_FAILED", () -> invoke("compile_sources", id, invalid));
        assertEquals(originalHash, call("inspect_class", id, "name", "demo.Sample").get("sha256").getAsString());
        assertEquals(0, call("archive_info", id).get("revision").getAsLong());

        String source = "package demo; public class Sample { public static int value() { return 19; } public static class Nested {} }";
        JsonObject compiled = call("compile_sources", id, "sources", Map.of("demo.Sample", source), "expectedRevision", 0);
        assertEquals(1, compiled.get("revision").getAsLong());
        assertTrue(compiled.getAsJsonArray("generatedClasses").contains(new JsonPrimitive("demo/Sample$Nested")));
        Path exported = directory.resolve("compiled.jar");
        call("export_archive", id, "path", exported.toString(), "expectedRevision", 1);
        byte[] output = entry(exported, "demo/Sample.class");
        Class<?> loaded = new ClassLoader() {
            Class<?> loadSample() {
                return defineClass("demo.Sample", output, 0, output.length);
            }
        }.loadSample();
        assertEquals(19, loaded.getMethod("value").invoke(null));
        assertNotNull(entry(exported, "demo/Sample$Nested.class"));
        assertEquals("original", new String(entry(exported, "message.txt"), StandardCharsets.UTF_8));

        JsonObject replaced = call("compile_sources", id, "sources", Map.of("demo.Sample",
                "package demo; public class Sample { public static int value() { return 21; } }"), "expectedRevision", 1);
        assertTrue(replaced.getAsJsonArray("removedClasses").contains(new JsonPrimitive("demo/Sample$Nested")));
        assertFailure("CLASS_NOT_FOUND", () -> call("inspect_class", id, "name", "demo.Sample$Nested"));
    }

    @Test
    void sourceWithoutRequestedTypeDoesNotDeleteExistingClass() throws Exception {
        String id = open(fixture("missing-type.jar"));
        String before = call("inspect_class", id, "name", "demo.Sample").get("sha256").getAsString();
        assertFailure("COMPILATION_FAILED", () -> call("compile_sources", id,
                "sources", Map.of("demo.Sample", "package demo;"), "expectedRevision", 0));
        assertEquals(before, call("inspect_class", id, "name", "demo.Sample").get("sha256").getAsString());
        assertEquals(0, call("archive_info", id).get("revision").getAsLong());
    }

    @Test
    void exportRequiresCurrentRevisionAndExplicitOverwrite() throws Exception {
        Path input = fixture("export.jar");
        byte[] original = Files.readAllBytes(input);
        String id = open(input);
        call("write_resource", id, "path", "message.txt", "content", "replacement", "expectedRevision", 0);
        assertFailure("IO_ERROR", () -> call("export_archive", id, "path", input.toString(), "expectedRevision", 1));
        assertArrayEquals(original, Files.readAllBytes(input));
        assertFailure("REVISION_CONFLICT", () -> call("export_archive", id,
                "path", input.toString(), "expectedRevision", 0, "overwrite", true));
        assertArrayEquals(original, Files.readAllBytes(input));
        call("export_archive", id, "path", input.toString(), "expectedRevision", 1, "overwrite", true);
        assertEquals("replacement", new String(entry(input, "message.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void unicodePagesDoNotSplitSurrogatesAndBinaryBytesStayExact() throws Exception {
        String id = open(fixture("resources.jar"));
        call("write_resource", id, "path", "unicode.txt", "content", "中😀文", "expectedRevision", 0);
        JsonObject page = call("read_resource", id, "path", "unicode.txt", "offset", 1, "limit", 1);
        assertEquals("😀", page.get("content").getAsString());
        assertEquals(2, page.get("nextOffset").getAsInt());
        assertEquals("文", call("read_resource", id, "path", "unicode.txt", "offset", 2).get("content").getAsString());
        assertFailure("INVALID_ENCODING", () -> call("read_resource", id, "path", "binary.bin"));
        String encoded = call("read_resource", id, "path", "binary.bin", "encoding", "base64").get("content").getAsString();
        assertArrayEquals(new byte[]{(byte) 0xff, 0, 42}, Base64.getDecoder().decode(encoded));
        call("write_resource", id, "path", "copy.bin", "encoding", "base64", "content", encoded, "expectedRevision", 1);
        Path output = directory.resolve("binary-output.jar");
        call("export_archive", id, "path", output.toString(), "expectedRevision", 2);
        assertArrayEquals(entry(output, "binary.bin"), entry(output, "copy.bin"));
    }

    @Test
    void classPaginationSearchAndMethodSelectionExposeRealBytecode() throws Exception {
        Path input = directory.resolve("search.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            put(zip, "demo/Zeta.class", classBytes("demo/Zeta"));
            put(zip, "demo/Alpha.class", classBytes("demo/Alpha"));
        }
        String id = open(input);
        JsonObject first = call("list_classes", id, "limit", 1);
        assertEquals("demo/Alpha", first.getAsJsonArray("classes").get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(first.get("hasMore").getAsBoolean());
        JsonObject next = call("list_classes", id, "offset", first.get("nextOffset").getAsInt(), "limit", 1);
        assertEquals("demo/Zeta", next.getAsJsonArray("classes").get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(next.get("hasMore").getAsBoolean());
        JsonArray strings = call("search_archive", id, "kind", "string", "query", "before").getAsJsonArray("matches");
        assertEquals(2, strings.size());
        assertEquals("message", strings.get(0).getAsJsonObject().get("method").getAsString());
        JsonArray references = call("search_archive", id, "kind", "reference", "query", "java.lang.Object").getAsJsonArray("matches");
        assertEquals("java/lang/Object", references.get(0).getAsJsonObject().get("owner").getAsString());
        JsonArray opcodes = call("search_archive", id, "kind", "opcode", "query", "IRETURN").getAsJsonArray("matches");
        assertEquals(2, opcodes.size());
        JsonObject assembly = call("disassemble_class", id, "name", "demo/Alpha.class", "method", "value", "descriptor", "()I");
        assertTrue(assembly.get("text").getAsString().contains("IRETURN"));
        assertFalse(assembly.get("text").getAsString().contains("LDC \"before\""));
        assertFailure("METHOD_NOT_FOUND", () -> call("disassemble_class", id,
                "name", "demo.Alpha", "method", "value", "descriptor", "()V"));
    }

    @Test
    void deletingExactEntriesPreservesUnrelatedContent() throws Exception {
        String id = open(fixture("delete.jar"));
        call("delete_entry", id, "path", "demo/Sample.class", "expectedRevision", 0);
        assertFailure("CLASS_NOT_FOUND", () -> call("inspect_class", id, "name", "demo.Sample"));
        assertEquals("original", call("read_resource", id, "path", "message.txt").get("content").getAsString());
        assertFailure("ENTRY_NOT_FOUND", () -> call("delete_entry", id, "path", "demo/Missing.class", "expectedRevision", 1));
        assertEquals(1, call("archive_info", id).get("revision").getAsLong());
    }

    private Path fixture(String filename) throws IOException {
        Path input = directory.resolve(filename);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            put(zip, "demo/Sample.class", classBytes("demo/Sample"));
            put(zip, "message.txt", "original".getBytes(StandardCharsets.UTF_8));
            put(zip, "binary.bin", new byte[]{(byte) 0xff, 0, 42});
        }
        return input;
    }

    private String open(Path input) throws ToolException {
        return tools.call("open_archive", arguments("path", input.toString())).get("archiveId").getAsString();
    }

    private JsonObject call(String tool, String id, Object... values) throws ToolException {
        return invoke(tool, id, arguments(values));
    }

    private JsonObject invoke(String tool, String id, JsonObject args) throws ToolException {
        args.addProperty("archiveId", id);
        return tools.call(tool, args);
    }

    private static JsonObject arguments(Object... values) {
        JsonObject args = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            args.add((String) values[i], ToolJson.GSON.toJsonTree(values[i + 1]));
        }
        return args;
    }

    private static void assertFailure(String code, org.junit.jupiter.api.function.Executable operation) {
        ToolException error = assertThrows(ToolException.class, operation);
        assertEquals(code, error.toJson().getAsJsonObject("error").get("code").getAsString());
    }

    private static byte[] entry(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull(entry, name);
            try (var input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] classBytes(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        value.visitCode();
        value.visitIntInsn(Opcodes.BIPUSH, 5);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(1, 0);
        value.visitEnd();
        MethodVisitor message = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "message", "()Ljava/lang/String;", null, null);
        message.visitCode();
        message.visitLdcInsn("before");
        message.visitInsn(Opcodes.ARETURN);
        message.visitMaxs(1, 0);
        message.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
