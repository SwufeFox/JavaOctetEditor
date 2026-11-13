package cn.enaium.joe.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ToolApplicationTest {
    @TempDir
    Path temp;

    @Test
    void discoversRealSchemasWithoutReadingStdin() {
        Reply reply = invoke(unreadableInput(), "--cli", "tools");
        assertEquals(0, reply.status());
        JsonArray definitions = reply.json().getAsJsonArray("tools");
        try (ArchiveTools tools = new ArchiveTools()) {
            assertEquals(tools.listTools(), definitions);
        }
        assertTrue(definitions.asList().stream().anyMatch(element ->
                element.getAsJsonObject().get("name").getAsString().equals("compile_sources")));
    }

    @Test
    void readsResourceWithInjectedHandleAndOptionsBeforeJson() throws Exception {
        Path source = archive();
        Reply reply = invoke(unreadableInput(), "--cli", "read_resource", "--archive", source.toString(),
                "{\"path\":\"message.txt\"}");
        assertEquals(0, reply.status());
        JsonObject result = reply.json().getAsJsonObject("result");
        assertEquals("original", result.get("content").getAsString());
        assertEquals(0, result.get("revision").getAsLong());
        assertFalse(result.get("archiveId").getAsString().isBlank());
    }

    @Test
    void writesAndExportsResultingRevisionWithoutChangingSource() throws Exception {
        Path source = archive();
        Path target = temp.resolve("edited.jar");
        Reply reply = invoke(unreadableInput(), "--cli", "write_resource", "--output", target.toString(),
                "{\"path\":\"message.txt\",\"content\":\"edited λ\"}", "--archive", source.toString());
        assertEquals(0, reply.status());
        JsonObject envelope = reply.json();
        JsonObject result = envelope.getAsJsonObject("result");
        JsonObject exported = envelope.getAsJsonObject("export");
        assertEquals(1, result.get("revision").getAsLong());
        assertEquals(result.get("revision"), exported.get("revision"));
        assertEquals(result.get("archiveId"), exported.get("archiveId"));
        assertEquals("edited λ", resource(target, "message.txt"));
        assertEquals("original", resource(source, "message.txt"));
    }

    @Test
    void compilesRealSourceAndExportsGeneratedClass() throws Exception {
        Path source = archive();
        Path target = temp.resolve("compiled.jar");
        JsonObject sources = new JsonObject();
        sources.addProperty("example.Generated", "package example; public class Generated { public int answer() { return 42; } }");
        JsonObject arguments = new JsonObject();
        arguments.add("sources", sources);
        Reply reply = invoke(unreadableInput(), "--cli", "compile_sources", arguments.toString(),
                "--archive", source.toString(), "--output", target.toString());
        assertEquals(0, reply.status());
        assertEquals(1, reply.json().getAsJsonObject("export").get("revision").getAsLong());
        try (ZipFile zip = new ZipFile(target.toFile())) {
            ZipEntry generated = zip.getEntry("example/Generated.class");
            assertNotNull(generated);
            try (InputStream input = zip.getInputStream(generated)) {
                byte[] header = input.readNBytes(4);
                assertArrayEquals(new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe}, header);
            }
        }
        assertEquals("original", resource(target, "message.txt"));
    }

    @Test
    void refusesExistingOutputUnlessOverwriteWasExplicit() throws Exception {
        Path source = archive();
        Path target = temp.resolve("existing.jar");
        byte[] original = "do not destroy".getBytes(StandardCharsets.UTF_8);
        Files.write(target, original);
        String[] command = {"--cli", "write_resource", "{\"path\":\"message.txt\",\"content\":\"replacement\"}",
                "--archive", source.toString(), "--output", target.toString()};
        Reply refused = invoke(unreadableInput(), command);
        assertEquals(1, refused.status());
        assertTrue(refused.json().has("error"));
        assertArrayEquals(original, Files.readAllBytes(target));

        String[] overwrite = java.util.Arrays.copyOf(command, command.length + 1);
        overwrite[command.length] = "--overwrite";
        Reply allowed = invoke(unreadableInput(), overwrite);
        assertEquals(0, allowed.status());
        assertEquals("replacement", resource(target, "message.txt"));
    }

    @Test
    void failedCompilationAndFailedToolNeverExport() throws Exception {
        Path source = archive();
        Path target = temp.resolve("failed.jar");
        Reply compilation = invoke(unreadableInput(), "--cli", "compile_sources",
                "{\"sources\":{\"Broken\":\"public class Broken { invalid java }\"}}",
                "--archive", source.toString(), "--output", target.toString());
        assertEquals(1, compilation.status());
        assertEquals("COMPILATION_FAILED", compilation.json().getAsJsonObject("error").get("code").getAsString());
        assertFalse(Files.exists(target));
        assertEquals("original", resource(source, "message.txt"));

        Reply tool = invoke(unreadableInput(), "--cli", "read_resource", "{\"path\":\"absent.txt\"}",
                "--archive", source.toString(), "--output", target.toString());
        assertEquals(1, tool.status());
        assertTrue(tool.json().has("error"));
        assertFalse(Files.exists(target));
    }

    @Test
    void explicitRevisionIsHonoredRatherThanOverwritten() throws Exception {
        Path source = archive();
        Path target = temp.resolve("conflict.jar");
        Reply reply = invoke(unreadableInput(), "--cli", "write_resource",
                "{\"path\":\"message.txt\",\"content\":\"bad\",\"expectedRevision\":5}",
                "--archive", source.toString(), "--output", target.toString());
        assertEquals(1, reply.status());
        assertEquals("REVISION_CONFLICT", reply.json().getAsJsonObject("error").get("code").getAsString());
        assertFalse(Files.exists(target));
    }

    @Test
    void readsUtf8ArgumentsFileAndExplicitStdinOnly() throws Exception {
        Path source = archive();
        Path json = temp.resolve("arguments.json");
        Files.writeString(json, "{\"path\":\"message.txt\",\"content\":\"雪\"}", StandardCharsets.UTF_8);
        Path target = temp.resolve("unicode.jar");
        Reply file = invoke(unreadableInput(), "--cli", "write_resource", "--archive", source.toString(),
                "@" + json, "--output", target.toString());
        assertEquals(0, file.status());
        assertEquals("雪", resource(target, "message.txt"));

        Reply stdin = invoke(new ByteArrayInputStream("{\"path\":\"message.txt\"}".getBytes(StandardCharsets.UTF_8)),
                "--cli", "read_resource", "-", "--archive", source.toString());
        assertEquals(0, stdin.status());
        assertEquals("original", stdin.json().getAsJsonObject("result").get("content").getAsString());

        Reply omitted = invoke(unreadableInput(), "--cli", "archive_info", "--archive", source.toString());
        assertEquals(0, omitted.status());
    }

    @Test
    void rejectsInvalidUsageAsOneJsonValue() {
        String[][] commands = {
                {}, {"--unknown"}, {"--version", "extra"}, {"--help", "extra"}, {"--mcp", "extra"},
                {"--cli"}, {"--cli", "absent"}, {"--cli", "tools", "{}"},
                {"--cli", "archive_info", "--unknown"}, {"--cli", "archive_info", "--archive"},
                {"--cli", "archive_info", "--output", "--overwrite"},
                {"--cli", "archive_info", "--archive", "a", "--archive", "b"},
                {"--cli", "archive_info", "--overwrite", "--overwrite", "--output", "a"},
                {"--cli", "archive_info", "--overwrite"}, {"--cli", "archive_info", "{}", "{}"},
                {"--cli", "archive_info", "[]"}, {"--cli", "archive_info", "{} {}"},
                {"--cli", "archive_info", "@" + temp.resolve("missing.json")},
                {"--cli", "archive_info", "{\"archiveId\":\"foreign\"}", "--archive", "unused"},
                {"--cli", "open_archive", "--archive", "unused"},
                {"--cli", "close_archive", "--output", "unused"}
        };
        for (String[] command : commands) {
            Reply reply = invoke(unreadableInput(), command);
            assertEquals(2, reply.status(), java.util.Arrays.toString(command));
            assertEquals("USAGE_ERROR", reply.json().getAsJsonObject("error").get("code").getAsString());
            assertFalse(reply.errors().isBlank());
        }
    }

    @Test
    void openArchiveCanExportAndHandlesDoNotSurviveAnInvocation() throws Exception {
        Path source = archive();
        Path target = temp.resolve("copy.jar");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", source.toString());
        Reply opened = invoke(unreadableInput(), "--cli", "open_archive", arguments.toString(),
                "--output", target.toString());
        assertEquals(0, opened.status());
        assertEquals("original", resource(target, "message.txt"));
        JsonObject handle = new JsonObject();
        handle.add("archiveId", opened.json().getAsJsonObject("result").get("archiveId"));
        Reply closed = invoke(unreadableInput(), "--cli", "archive_info", handle.toString());
        assertEquals(1, closed.status());
        assertEquals("ARCHIVE_NOT_FOUND", closed.json().getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void helpVersionAndMcpEofDoNotChangeGlobalStreams() {
        PrintStream originalOutput = System.out;
        PrintStream originalErrors = System.err;
        InputStream originalInput = System.in;
        Reply help = invoke(unreadableInput(), "--help");
        assertEquals(0, help.status());
        assertTrue(help.output().contains("--cli"));
        assertTrue(help.output().contains("--mcp"));
        Reply version = invoke(unreadableInput(), "--version");
        assertEquals(0, version.status());
        assertEquals(ToolApplication.VERSION, version.json().get("version").getAsString());
        Reply mcp = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> invoke(InputStream.nullInputStream(), "--mcp"));
        assertEquals(0, mcp.status());
        assertTrue(mcp.output().isEmpty());
        assertSame(originalOutput, System.out);
        assertSame(originalErrors, System.err);
        assertSame(originalInput, System.in);
    }

    private Path archive() throws Exception {
        Path path = temp.resolve("input.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("message.txt"));
            zip.write("original".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static String resource(Path archive, String name) throws Exception {
        try (ZipFile zip = new ZipFile(archive.toFile()); InputStream input = zip.getInputStream(zip.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static InputStream unreadableInput() {
        return new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("stdin must only be read when '-' was explicitly supplied");
            }
        };
    }

    private static Reply invoke(InputStream input, String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = ToolApplication.run(args, input, new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        return new Reply(status, output.toString(StandardCharsets.UTF_8), errors.toString(StandardCharsets.UTF_8));
    }

    private record Reply(int status, String output, String errors) {
        JsonObject json() {
            JsonElement value = ToolJson.parse(output);
            assertTrue(value.isJsonObject());
            return value.getAsJsonObject();
        }
    }
}
