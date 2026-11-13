package cn.enaium.joe.tool;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ArchiveToolsConcurrencyTest {
    @TempDir
    Path temporary;

    private final ArchiveTools tools = new ArchiveTools();
    private final ExecutorService callers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "archive-concurrency-test");
        thread.setDaemon(true);
        return thread;
    });

    @AfterEach
    void close() {
        tools.close();
        callers.shutdownNow();
    }

    @Test
    void independentHandlesProgressWhileAnotherWorkspaceCallIsPaused() throws Exception {
        Path input = fixture();
        String first = open(input);
        String second = open(input);
        try (PausedCancellation paused = new PausedCancellation()) {
            Future<JsonObject> held = callers.submit(() -> tools.call("write_resource", write(first, "first"), paused));
            paused.awaitEntered();
            JsonObject changed = callers.submit(() -> tools.call("write_resource", write(second, "second")))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(1, changed.get("revision").getAsLong());
            assertEquals("second", read(second));
            paused.release();
            assertEquals(1, held.get(3, TimeUnit.SECONDS).get("revision").getAsLong());
            assertEquals("first", read(first));
        }
    }

    @Test
    void competingWritesCheckTheirRevisionInsideTheWorkspaceLock() throws Exception {
        String id = open(fixture());
        CountDownLatch contenderWaiting = new CountDownLatch(1);
        try (PausedCancellation paused = new PausedCancellation()) {
            Future<JsonObject> first = callers.submit(() -> tools.call("write_resource", write(id, "first"), paused));
            paused.awaitEntered();
            Future<ToolException> second = callers.submit(() -> {
                try {
                    tools.call("write_resource", write(id, "second"), () -> {
                        contenderWaiting.countDown();
                        return false;
                    });
                    return null;
                } catch (ToolException e) {
                    return e;
                }
            });
            assertTrue(contenderWaiting.await(3, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            paused.release();
            assertEquals(1, first.get(3, TimeUnit.SECONDS).get("revision").getAsLong());
            ToolException conflict = second.get(3, TimeUnit.SECONDS);
            assertNotNull(conflict);
            assertEquals("REVISION_CONFLICT", conflict.toJson().getAsJsonObject("error").get("code").getAsString());
            assertEquals("first", read(id));
            assertEquals(1, tools.call("archive_info", args("archiveId", id)).get("revision").getAsLong());
        }
    }

    @Test
    void anInterruptedCallerStopsWaitingForItsWorkspace() throws Exception {
        String id = open(fixture());
        CountDownLatch contenderWaiting = new CountDownLatch(1);
        AtomicReference<Thread> contenderThread = new AtomicReference<>();
        try (PausedCancellation paused = new PausedCancellation()) {
            Future<JsonObject> held = callers.submit(() -> tools.call("archive_info", args("archiveId", id), paused));
            paused.awaitEntered();
            Future<Boolean> contender = callers.submit(() -> {
                contenderThread.set(Thread.currentThread());
                assertThrows(CancellationException.class, () -> tools.call("write_resource", write(id, "cancelled"), () -> {
                    contenderWaiting.countDown();
                    return false;
                }));
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(contenderWaiting.await(3, TimeUnit.SECONDS));
            contenderThread.get().interrupt();
            assertTrue(contender.get(3, TimeUnit.SECONDS));
            paused.release();
            held.get(3, TimeUnit.SECONDS);
            assertEquals("original", read(id));
        }
    }

    @Test
    void registryCloseInvalidatesRunningAndWaitingCallsBeforeTheirBarrierReleases() throws Exception {
        String id = open(fixture());
        Path output = temporary.resolve("cancelled.jar");
        CountDownLatch contenderWaiting = new CountDownLatch(1);
        try (PausedCancellation paused = new PausedCancellation()) {
            Future<JsonObject> held = callers.submit(() -> tools.call("export_archive",
                    args("archiveId", id, "path", output.toString(), "expectedRevision", 0), paused));
            paused.awaitEntered();
            Future<JsonObject> contender = callers.submit(() -> tools.call("write_resource", write(id, "late"), () -> {
                contenderWaiting.countDown();
                return false;
            }));
            assertTrue(contenderWaiting.await(3, TimeUnit.SECONDS));
            callers.submit(tools::close).get(3, TimeUnit.SECONDS);
            assertCancelled(contender);
            assertThrows(CancellationException.class, () -> tools.call("open_archive", args("path", temporary.toString())));
            paused.release();
            assertCancelled(held);
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void closingAHandleInvalidatesItsInFlightMutationWithoutBlockingOtherHandles() throws Exception {
        Path input = fixture();
        String first = open(input);
        String second = open(input);
        try (PausedCancellation paused = new PausedCancellation()) {
            Future<JsonObject> held = callers.submit(() -> tools.call("write_resource", write(first, "late"), paused));
            paused.awaitEntered();
            JsonObject closed = callers.submit(() -> tools.call("close_archive", args("archiveId", first)))
                    .get(3, TimeUnit.SECONDS);
            assertTrue(closed.get("closed").getAsBoolean());
            assertEquals("original", read(second));
            paused.release();
            assertCancelled(held);
            ToolException missing = assertThrows(ToolException.class,
                    () -> tools.call("archive_info", args("archiveId", first)));
            assertEquals("ARCHIVE_NOT_FOUND", missing.toJson().getAsJsonObject("error").get("code").getAsString());
        }
    }

    @Test
    void cancellationTokenPreventsMutationEvenWhenAnInterruptIsConsumed() throws Exception {
        String id = open(fixture());
        try (PausedCancellation paused = new PausedCancellation()) {
            Future<JsonObject> held = callers.submit(() -> tools.call("write_resource", write(id, "late"), paused));
            paused.awaitEntered();
            paused.cancelled.set(true);
            paused.caller.get().interrupt();
            paused.release();
            assertCancelled(held);
            assertFalse(paused.interruptedAfterRelease.get());
            assertEquals("original", read(id));
            assertEquals(0, tools.call("archive_info", args("archiveId", id)).get("revision").getAsLong());
        }
    }

    private Path fixture() throws Exception {
        Path input = Files.createDirectory(temporary.resolve("input"));
        Files.writeString(input.resolve("data.txt"), "original");
        return input;
    }

    private String open(Path input) throws ToolException {
        return tools.call("open_archive", args("path", input.toString())).get("archiveId").getAsString();
    }

    private String read(String id) throws ToolException {
        return tools.call("read_resource", args("archiveId", id, "path", "data.txt")).get("content").getAsString();
    }

    private static JsonObject write(String id, String content) {
        return args("archiveId", id, "path", "data.txt", "content", content, "expectedRevision", 0);
    }

    static JsonObject args(Object... fields) {
        JsonObject result = new JsonObject();
        for (int index = 0; index < fields.length; index += 2) {
            result.add((String) fields[index], ToolJson.GSON.toJsonTree(fields[index + 1]));
        }
        return result;
    }

    private static void assertCancelled(Future<?> future) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, failure.getCause());
    }

    /** A cooperative cancellation boundary which deliberately consumes library-style interrupts. */
    static final class PausedCancellation implements BooleanSupplier, AutoCloseable {
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<Thread> caller = new AtomicReference<>();
        final AtomicBoolean interruptedAfterRelease = new AtomicBoolean();

        @Override
        public boolean getAsBoolean() {
            if (first.compareAndSet(true, false)) {
                caller.set(Thread.currentThread());
                entered.countDown();
                boolean waiting = true;
                while (waiting) {
                    try {
                        released.await();
                        waiting = false;
                    } catch (InterruptedException ignored) {
                        // A compiler/decompiler is allowed to consume its interrupt flag.
                    }
                }
                Thread.interrupted();
                interruptedAfterRelease.set(Thread.currentThread().isInterrupted());
            }
            return cancelled.get();
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        }

        void release() {
            released.countDown();
        }

        @Override
        public void close() {
            release();
        }
    }
}
