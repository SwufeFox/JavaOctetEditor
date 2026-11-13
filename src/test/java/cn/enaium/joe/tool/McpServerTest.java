package cn.enaium.joe.tool;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class McpServerTest {
    private static final String VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final String CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";

    @TempDir
    Path temporary;

    @Test
    void discoveryAdvertisesOnlyImplementedFeaturesAndWorkspaceGuidance() throws Exception {
        JsonObject response = session(modern(1, "server/discover", new JsonObject())).getFirst();
        JsonObject result = response.getAsJsonObject("result");
        assertEquals("complete", result.get("resultType").getAsString());
        assertEquals(Set.of("tools"), result.getAsJsonObject("capabilities").keySet());
        assertFalse(result.getAsJsonObject("capabilities").getAsJsonObject("tools").has("listChanged"));
        JsonArray versions = result.getAsJsonArray("supportedVersions");
        assertTrue(versions.contains(new JsonPrimitive("2026-07-28")));
        assertTrue(versions.contains(new JsonPrimitive("2025-11-25")));
        assertTrue(versions.contains(new JsonPrimitive("2025-06-18")));
        JsonObject server = result.getAsJsonObject("_meta").getAsJsonObject("io.modelcontextprotocol/serverInfo");
        assertEquals("JavaOctetEditor", server.get("name").getAsString());
        assertEquals("test-version", server.get("version").getAsString());
        String instructions = result.get("instructions").getAsString();
        for (String guidance : List.of("archiveId", "expectedRevision", "untrusted", "compile_sources", "export_archive", "close_archive")) {
            assertTrue(instructions.contains(guidance), guidance);
        }
    }

    @Test
    void everyModernRequestCarriesItsOwnVersionAndCapabilities() throws Exception {
        JsonObject missingCapabilities = modern(3, "tools/list", new JsonObject());
        missingCapabilities.getAsJsonObject("params").getAsJsonObject("_meta").remove(CAPABILITIES_KEY);
        JsonObject missingVersion = modern(4, "ping", new JsonObject());
        missingVersion.getAsJsonObject("params").getAsJsonObject("_meta").remove(VERSION_KEY);
        JsonObject unsupported = modern(5, "server/discover", new JsonObject());
        unsupported.getAsJsonObject("params").getAsJsonObject("_meta").addProperty(VERSION_KEY, "1900-01-01");
        List<JsonObject> responses = session(modern(1, "server/discover", new JsonObject()),
                request(2, "tools/list", new JsonObject()), missingCapabilities, missingVersion, unsupported,
                modern(6, "ping", new JsonObject()));
        assertError(responses.get(1), -32602);
        assertError(responses.get(2), -32602);
        assertError(responses.get(3), -32602);
        assertError(responses.get(4), -32022);
        JsonObject data = responses.get(4).getAsJsonObject("error").getAsJsonObject("data");
        assertEquals("1900-01-01", data.get("requested").getAsString());
        assertTrue(data.getAsJsonArray("supported").contains(new JsonPrimitive("2026-07-28")));
        assertEquals("complete", responses.getLast().getAsJsonObject("result").get("resultType").getAsString());
    }

    @Test
    void malformedMetadataAndClientIdentityAreInvalidParams() throws Exception {
        JsonObject malformed = modern(1, "tools/list", new JsonObject());
        malformed.getAsJsonObject("params").addProperty("_meta", "invalid");
        JsonObject capabilities = modern(2, "tools/list", new JsonObject());
        capabilities.getAsJsonObject("params").getAsJsonObject("_meta").add(CAPABILITIES_KEY, new JsonArray());
        JsonObject client = modern(3, "tools/list", new JsonObject());
        client.getAsJsonObject("params").getAsJsonObject("_meta").add("io.modelcontextprotocol/clientInfo", new JsonObject());
        for (JsonObject response : session(malformed, capabilities, client)) {
            assertError(response, -32602);
        }
    }

    @Test
    void bothLegacyVersionsInitializeThenRequireInitializedNotification() throws Exception {
        for (String protocol : List.of("2025-11-25", "2025-06-18")) {
            JsonObject notification = notification("notifications/initialized", new JsonObject());
            List<JsonObject> responses = session(initialize(protocol), request(2, "ping", new JsonObject()),
                    request(3, "tools/list", new JsonObject()), notification,
                    request(4, "tools/list", new JsonObject()), modern(5, "ping", new JsonObject()));
            assertEquals(5, responses.size());
            JsonObject initialized = responses.getFirst().getAsJsonObject("result");
            assertEquals(protocol, initialized.get("protocolVersion").getAsString());
            assertEquals("test-version", initialized.getAsJsonObject("serverInfo").get("version").getAsString());
            assertEquals(Set.of("tools"), initialized.getAsJsonObject("capabilities").keySet());
            assertFalse(responses.get(1).getAsJsonObject("result").has("resultType"));
            assertError(responses.get(2), -32600);
            JsonArray listed = responses.get(3).getAsJsonObject("result").getAsJsonArray("tools");
            assertTrue(listed.asList().stream().anyMatch(tool -> tool.getAsJsonObject().get("name").getAsString().equals("compile_sources")));
            assertEquals("complete", responses.get(4).getAsJsonObject("result").get("resultType").getAsString());
        }
    }

    @Test
    void legacyUnknownVersionNegotiatesAndCannotReinitialize() throws Exception {
        List<JsonObject> responses = session(initialize("1900-01-01"), initialize("2025-06-18"));
        assertEquals("2025-11-25", responses.getFirst().getAsJsonObject("result").get("protocolVersion").getAsString());
        assertError(responses.getLast(), -32600);
    }

    @Test
    void fixedCatalogAndUnsupportedMethodsHaveProtocolResponses() throws Exception {
        JsonObject cursor = new JsonObject();
        cursor.addProperty("cursor", "invented");
        List<JsonObject> responses = session(modern(1, "tools/list", new JsonObject()),
                modern(2, "tools/list", cursor), modern(3, "resources/list", new JsonObject()),
                modern(4, "prompts/list", new JsonObject()), modern(5, "logging/setLevel", new JsonObject()));
        JsonArray catalog = responses.getFirst().getAsJsonObject("result").getAsJsonArray("tools");
        assertFalse(catalog.isEmpty());
        assertEquals(catalog.size(), catalog.asList().stream().map(tool -> tool.getAsJsonObject().get("name").getAsString()).distinct().count());
        assertError(responses.get(1), -32602);
        for (int index = 2; index < responses.size(); index++) {
            assertError(responses.get(index), -32601);
        }
    }

    @Test
    void realToolCallsPreserveUnicodeRevisionsAndActionableErrors() throws Exception {
        Path archive = Files.createDirectory(temporary.resolve("input"));
        String text = "héllo 世界\nsecond line";
        Files.writeString(archive.resolve("message.txt"), text, StandardCharsets.UTF_8);
        try (Harness harness = new Harness()) {
            JsonObject opened = structured(harness.call(1, "open_archive", object("path", archive.toString())));
            String archiveId = opened.get("archiveId").getAsString();
            assertEquals(1, opened.get("resourceCount").getAsInt());
            long revision = opened.get("revision").getAsLong();
            JsonObject read = harness.call(2, "read_resource", object("archiveId", archiveId, "path", "message.txt"));
            assertFalse(read.getAsJsonObject("result").get("isError").getAsBoolean());
            assertEquals(text, structured(read).get("content").getAsString());
            assertEquals(structured(read), ToolJson.parse(read.getAsJsonObject("result").getAsJsonArray("content")
                    .get(0).getAsJsonObject().get("text").getAsString()));
            JsonObject changed = structured(harness.call(3, "write_resource", object("archiveId", archiveId,
                    "path", "message.txt", "content", "changed", "expectedRevision", revision)));
            assertEquals(revision + 1, changed.get("revision").getAsLong());
            JsonObject conflict = harness.call(4, "write_resource", object("archiveId", archiveId,
                    "path", "message.txt", "content", "stale", "expectedRevision", revision));
            assertToolError(conflict, "REVISION_CONFLICT");
            assertEquals(revision + 1, structured(conflict).getAsJsonObject("error").getAsJsonObject("details").get("revision").getAsLong());
            JsonObject invalid = harness.call(5, "read_resource", new JsonObject());
            assertTrue(invalid.getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(structured(invalid).getAsJsonObject("error").get("message").getAsString().contains("archiveId"));
            assertTrue(structured(harness.call(6, "close_archive", object("archiveId", archiveId))).get("closed").getAsBoolean());
            assertToolError(harness.call(7, "archive_info", object("archiveId", archiveId)), "ARCHIVE_NOT_FOUND");
            harness.send(modern(8, "tools/call", object("name", "no_such_tool", "arguments", new JsonObject())));
            assertError(harness.response(8), -32602);
            harness.send(modern(9, "tools/call", object("name", "open_archive", "arguments", "not an object")));
            assertError(harness.response(9), -32602);
        }
    }

    @Test
    void legacySessionExecutesActualToolsWithoutModernMetadata() throws Exception {
        Path archive = Files.createDirectory(temporary.resolve("legacy"));
        Files.writeString(archive.resolve("data.txt"), "legacy");
        try (Harness harness = new Harness()) {
            harness.send(initialize("2025-06-18"));
            harness.response(1);
            harness.send(notification("notifications/initialized", new JsonObject()));
            harness.send(request(2, "tools/call", object("name", "open_archive", "arguments", object("path", archive.toString()))));
            JsonObject response = harness.response(2);
            assertFalse(response.getAsJsonObject("result").has("resultType"));
            assertEquals(1, structured(response).get("resourceCount").getAsInt());
        }
    }

    @Test
    void invalidJsonAndInvalidIdsCannotExecuteAndNotificationsNeverReply() throws Exception {
        try (Harness harness = new Harness()) {
            harness.raw("{bad json}\n");
            assertError(harness.next(), -32700);
            harness.raw("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",}\n");
            assertError(harness.next(), -32700);
            harness.raw("{} {}\n");
            assertError(harness.next(), -32700);
            harness.raw("[]\n");
            assertError(harness.next(), -32600);
            for (JsonElement id : List.of(JsonNull.INSTANCE, new JsonPrimitive(true), new JsonPrimitive(1.5), new JsonArray())) {
                JsonObject invalid = modern(1, "ping", new JsonObject());
                invalid.add("id", id);
                harness.send(invalid);
                JsonObject error = harness.next();
                assertError(error, -32600);
                assertFalse(error.has("id"), "An unreadable request ID must be omitted from an MCP error");
            }
            harness.send(notification("tools/call", object("name", "open_archive", "arguments", object("path", "missing"))));
            harness.send(notification("unknown/notification", new JsonObject()));
            JsonObject malformedNotification = notification("ping", new JsonObject());
            malformedNotification.addProperty("params", "invalid");
            harness.send(malformedNotification);
            harness.send(modern(100, "ping", new JsonObject()));
            harness.response(100);
            JsonObject stringId = modern(1, "ping", new JsonObject());
            stringId.addProperty("id", "client-request");
            harness.send(stringId);
            assertEquals("client-request", harness.next().get("id").getAsString());
            assertTrue(harness.output.frames.isEmpty());
        }
    }

    @Test
    void integerRequestIdsPreserveTheirValueWithoutExpandingScientificNotation() throws Exception {
        try (Harness harness = new Harness()) {
            JsonObject request = modern(1, "ping", new JsonObject());
            request.add("id", ToolJson.parse("1e2147483647"));
            harness.send(request);
            JsonObject response = harness.next();
            assertFalse(response.has("error"), response::toString);
            assertEquals("1e2147483647", response.get("id").getAsString());
        }
    }

    @Test
    void independentHandlesProgressWhileCancellationSuppressesALateMutation() throws Exception {
        Path archive = Files.createDirectory(temporary.resolve("cancelled"));
        Files.writeString(archive.resolve("data.txt"), "original");
        try (Harness harness = new Harness();
             ArchiveToolsConcurrencyTest.PausedCancellation paused = new ArchiveToolsConcurrencyTest.PausedCancellation()) {
            String first = harness.open(archive);
            String second = harness.open(archive);
            Future<JsonObject> held = harness.hold(first, paused);
            paused.awaitEntered();
            harness.send(modern(1, "tools/call", object("name", "write_resource", "arguments",
                    object("archiveId", first, "path", "data.txt", "content", "late", "expectedRevision", 0))));
            assertEquals("original", structured(harness.call(2, "read_resource",
                    object("archiveId", second, "path", "data.txt"))).get("content").getAsString());
            JsonObject duplicate = modern(1, "ping", new JsonObject());
            duplicate.add("id", ToolJson.parse("1.0e0"));
            harness.send(duplicate);
            assertError(harness.response(1), -32600);
            harness.send(notification("notifications/cancelled", object("requestId", 1, "reason", 123)));
            for (JsonElement malformedId : List.of(JsonNull.INSTANCE, new JsonPrimitive(true),
                    new JsonPrimitive(1.5), new JsonObject())) {
                JsonObject params = new JsonObject();
                params.add("requestId", malformedId);
                harness.send(notification("notifications/cancelled", params));
            }
            harness.send(notification("notifications/cancelled", object("requestId", 1, "_meta", "invalid")));
            harness.send(modern(3, "ping", new JsonObject()));
            harness.response(3);
            harness.send(modern(1, "ping", new JsonObject()));
            assertError(harness.response(1), -32600);
            harness.send(notification("notifications/cancelled", object("requestId", 1, "reason", "stop")));
            harness.send(modern(4, "ping", new JsonObject()));
            harness.response(4);
            paused.release();
            held.get(3, TimeUnit.SECONDS);
            assertEquals("original", structured(harness.call(5, "read_resource",
                    object("archiveId", first, "path", "data.txt"))).get("content").getAsString());
            assertTrue(harness.output.frames.isEmpty(), "A cancelled request must not emit a late response");
        }
    }

    @Test
    void queuedCancellationFreesCapacityAndOverflowStillReceivesAReply() throws Exception {
        Path archive = Files.createDirectory(temporary.resolve("queue"));
        Files.writeString(archive.resolve("data.txt"), "original");
        try (Harness harness = new Harness(1, 1);
             ArchiveToolsConcurrencyTest.PausedCancellation paused = new ArchiveToolsConcurrencyTest.PausedCancellation()) {
            String first = harness.open(archive);
            String second = harness.open(archive);
            Future<JsonObject> held = harness.hold(first, paused);
            paused.awaitEntered();
            JsonObject mutation = object("name", "write_resource", "arguments",
                    object("archiveId", first, "path", "data.txt", "content", "late", "expectedRevision", 0));
            harness.send(modern(1, "tools/call", mutation.deepCopy()));
            harness.send(modern(2, "ping", new JsonObject()));
            harness.response(2);
            harness.send(modern(3, "tools/call", mutation.deepCopy()));
            harness.send(modern(4, "ping", new JsonObject()));
            harness.response(4);
            harness.send(modern(5, "tools/call", mutation.deepCopy()));
            assertError(harness.response(5), -32603);
            harness.send(notification("notifications/cancelled", object("requestId", 3)));
            harness.send(modern(6, "ping", new JsonObject()));
            harness.response(6);
            harness.send(modern(7, "tools/call", object("name", "write_resource", "arguments",
                    object("archiveId", second, "path", "data.txt", "content", "accepted", "expectedRevision", 0))));
            harness.send(notification("notifications/cancelled", object("requestId", 1)));
            assertEquals(1, structured(harness.response(7)).get("revision").getAsLong());
            paused.release();
            held.get(3, TimeUnit.SECONDS);
            assertEquals("original", structured(harness.call(8, "read_resource",
                    object("archiveId", first, "path", "data.txt"))).get("content").getAsString());
            assertTrue(harness.output.frames.isEmpty());
        }
    }

    @Test
    void eofCancelsWorkBeforeAWorkspaceBarrierReleasesAndServerCannotRestart() throws Exception {
        Path archive = Files.createDirectory(temporary.resolve("eof"));
        Files.writeString(archive.resolve("data.txt"), "original");
        try (Harness harness = new Harness();
             ArchiveToolsConcurrencyTest.PausedCancellation paused = new ArchiveToolsConcurrencyTest.PausedCancellation()) {
            String id = harness.open(archive);
            Future<JsonObject> held = harness.hold(id, paused);
            paused.awaitEntered();
            harness.send(modern(1, "tools/call", object("name", "write_resource", "arguments",
                    object("archiveId", id, "path", "data.txt", "content", "late", "expectedRevision", 0))));
            harness.send(modern(2, "ping", new JsonObject()));
            harness.response(2);
            harness.client.close();
            harness.finished.get(3, TimeUnit.SECONDS);
            assertTrue(harness.output.frames.isEmpty());
            assertThrows(IllegalStateException.class, () -> harness.server.run(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
            paused.release();
            held.get(3, TimeUnit.SECONDS);
            assertEquals("original", harness.tools.call("read_resource",
                    object("archiveId", id, "path", "data.txt")).get("content").getAsString());
        }
    }

    @Test
    void independentlyAbortedOperationsStillReceiveAnActionableReply() throws Exception {
        try (Harness harness = new Harness()) {
            harness.tools.close();
            assertToolError(harness.call(1, "archive_info", object("archiveId", "closed-registry")), "OPERATION_ABORTED");
            harness.send(modern(2, "ping", new JsonObject()));
            assertEquals("complete", harness.response(2).getAsJsonObject("result").get("resultType").getAsString());
        }
    }

    @Test
    void malformedUtf8ClosesTheTransportWithoutWritingInvalidFrames() throws Exception {
        for (byte[] bytes : List.of(new byte[]{(byte) 0xc3, 0x28, '\n'}, new byte[]{(byte) 0xc3})) {
            try (ArchiveTools tools = new ArchiveTools(); McpServer server = new McpServer(tools, "test-version")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                assertThrows(java.nio.charset.CharacterCodingException.class,
                        () -> server.run(new ByteArrayInputStream(bytes), output));
                assertEquals(0, output.size());
            }
        }
    }

    @Test
    void workerWriteFailuresUnblockTheReaderAndPreserveTheTransportFailure() throws Exception {
        ExecutorService runner = Executors.newSingleThreadExecutor();
        try (ArchiveTools tools = new ArchiveTools(); McpServer server = new McpServer(tools, "test-version");
             PipedInputStream input = new PipedInputStream(65536);
             PipedOutputStream client = new PipedOutputStream(input)) {
            Future<?> finished = runner.submit(() -> {
                server.run(input, new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("broken worker transport");
                    }
                });
                return null;
            });
            JsonObject request = modern(1, "tools/call", object("name", "archive_info",
                    "arguments", object("archiveId", "missing")));
            client.write((request + "\n").getBytes(StandardCharsets.UTF_8));
            client.flush();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> finished.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals("broken worker transport", failure.getCause().getMessage());
        } finally {
            runner.shutdownNow();
        }
    }

    @Test
    void outputFailuresPropagateAndDoNotLeaveReaderRunning() throws Exception {
        try (ArchiveTools tools = new ArchiveTools(); McpServer server = new McpServer(tools, "test-version")) {
            byte[] input = (ToolJson.GSON.toJson(modern(1, "ping", new JsonObject())) + "\n").getBytes(StandardCharsets.UTF_8);
            IOException failure = assertThrows(IOException.class, () -> server.run(new ByteArrayInputStream(input), new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw new IOException("broken transport");
                }
            }));
            assertEquals("broken transport", failure.getMessage());
        }
    }

    private static List<JsonObject> session(JsonObject... requests) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StringBuilder input = new StringBuilder();
        for (JsonObject request : requests) {
            input.append(ToolJson.GSON.toJson(request)).append('\n');
        }
        try (ArchiveTools tools = new ArchiveTools(); McpServer server = new McpServer(tools, "test-version")) {
            server.run(new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)), output);
        }
        List<JsonObject> responses = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
            responses.add(ToolJson.object(line));
        }
        return responses;
    }

    private static JsonObject modern(int id, String method, JsonObject params) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty(VERSION_KEY, "2026-07-28");
        metadata.add(CAPABILITIES_KEY, new JsonObject());
        params.add("_meta", metadata);
        return request(id, method, params);
    }

    private static JsonObject request(int id, String method, JsonObject params) {
        JsonObject request = notification(method, params);
        request.addProperty("id", id);
        return request;
    }

    private static JsonObject notification(String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("method", method);
        request.add("params", params);
        return request;
    }

    private static JsonObject initialize(String version) {
        return request(1, "initialize", object("protocolVersion", version, "capabilities", new JsonObject(),
                "clientInfo", object("name", "tests", "version", "1")));
    }

    private static JsonObject object(Object... fields) {
        JsonObject result = new JsonObject();
        for (int index = 0; index < fields.length; index += 2) {
            result.add((String) fields[index], ToolJson.GSON.toJsonTree(fields[index + 1]));
        }
        return result;
    }

    private static JsonObject structured(JsonObject response) {
        assertFalse(response.has("error"), response::toString);
        return response.getAsJsonObject("result").getAsJsonObject("structuredContent");
    }

    private static void assertError(JsonObject response, int code) {
        assertFalse(response.has("result"), response::toString);
        assertEquals(code, response.getAsJsonObject("error").get("code").getAsInt(), response::toString);
    }

    private static void assertToolError(JsonObject response, String code) {
        assertFalse(response.has("error"), response::toString);
        assertTrue(response.getAsJsonObject("result").get("isError").getAsBoolean(), response::toString);
        assertEquals(code, structured(response).getAsJsonObject("error").get("code").getAsString());
    }

    private static final class Harness implements AutoCloseable {
        private final ArchiveTools tools = new ArchiveTools();
        private final McpServer server;
        private final FrameOutput output = new FrameOutput();
        private final PipedOutputStream client;
        private final ExecutorService runner = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-test-reader");
            thread.setDaemon(true);
            return thread;
        });
        private final ExecutorService operations = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mcp-test-workspace");
            thread.setDaemon(true);
            return thread;
        });
        private final Future<?> finished;

        private Harness() throws IOException {
            this(4, 128);
        }

        private Harness(int parallelism, int queueCapacity) throws IOException {
            server = new McpServer(tools, "test-version", parallelism, queueCapacity);
            PipedInputStream input = new PipedInputStream(65536);
            client = new PipedOutputStream(input);
            finished = runner.submit(() -> {
                server.run(input, output);
                return null;
            });
        }

        private String open(Path archive) throws ToolException {
            return tools.call("open_archive", object("path", archive.toString())).get("archiveId").getAsString();
        }

        private Future<JsonObject> hold(String id, ArchiveToolsConcurrencyTest.PausedCancellation paused) {
            return operations.submit(() -> tools.call("archive_info", object("archiveId", id), paused));
        }

        private void raw(String text) throws IOException {
            client.write(text.getBytes(StandardCharsets.UTF_8));
            client.flush();
        }

        private void send(JsonObject request) throws IOException {
            raw(request.toString() + "\n");
        }

        private JsonObject next() throws InterruptedException {
            String frame = output.frames.poll(3, TimeUnit.SECONDS);
            assertNotNull(frame, "No MCP response arrived");
            return ToolJson.object(frame);
        }

        private JsonObject response(int id) throws InterruptedException {
            JsonObject response = next();
            assertEquals(id, response.get("id").getAsInt(), response::toString);
            return response;
        }

        private JsonObject call(int id, String name, JsonObject arguments) throws Exception {
            send(modern(id, "tools/call", object("name", name, "arguments", arguments)));
            return response(id);
        }

        @Override
        public void close() throws Exception {
            client.close();
            try {
                finished.get(3, TimeUnit.SECONDS);
            } finally {
                server.close();
                runner.shutdownNow();
                operations.shutdownNow();
                tools.close();
            }
        }
    }

    private static final class FrameOutput extends OutputStream {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int value) {
            if (value == '\n') {
                frames.add(frame.toString(StandardCharsets.UTF_8));
                frame.reset();
            } else {
                frame.write(value);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                write(bytes[index] & 0xff);
            }
        }
    }
}
