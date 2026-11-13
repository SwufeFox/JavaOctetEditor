package cn.enaium.joe.util.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TaskManagerTest {
    @Test
    void inlineCompletionCannotLeaveRegisteredTask() throws Exception {
        InlineExecutor executor = new InlineExecutor();
        try (TaskManager manager = new TaskManager(executor, failure -> fail(failure))) {
            CompletableFuture<Integer> result = manager.submit(new ImmediateTask());
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertTrue(manager.getTask().isEmpty());
            assertEquals(42, manager.submit(new ImmediateTask()).get(5, TimeUnit.SECONDS));
            assertThrows(UnsupportedOperationException.class, () -> manager.getTask().clear());
        }
        assertTrue(executor.isShutdown());
    }

    @Test
    void resultAndFailureHandlersRunOnEventThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        AtomicBoolean errorOnEdt = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        try (TaskManager manager = new TaskManager(executor, failure -> {
            errorOnEdt.set(SwingUtilities.isEventDispatchThread());
            errors.add(failure);
        })) {
            CompletableFuture<Integer> result = manager.submit(new BlockingTask(release, new CountDownLatch(0)));
            CompletableFuture<Boolean> callback = result.thenApply(value -> SwingUtilities.isEventDispatchThread());
            release.countDown();
            assertTrue(callback.get(5, TimeUnit.SECONDS));
            CompletableFuture<Object> failure = manager.submit(new AbstractTask<>("Failure") {
                @Override public Object get() { throw new IllegalArgumentException("broken"); }
            });
            assertInstanceOf(IllegalArgumentException.class,
                    assertThrows(ExecutionException.class, () -> failure.get(5, TimeUnit.SECONDS)).getCause());
            SwingUtilities.invokeAndWait(() -> {});
            assertTrue(errorOnEdt.get());
            assertEquals(1, errors.size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void simultaneousSubmissionsReserveOneNonrepeatableTask() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        ExecutorService submitters = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        CyclicBarrier start = new CyclicBarrier(2);
        try (TaskManager manager = new TaskManager(executor, failure -> {})) {
            Callable<CompletableFuture<Integer>> submit = () -> {
                start.await();
                return manager.submit(new BlockingTask(release, new CountDownLatch(0)));
            };
            Future<CompletableFuture<Integer>> a = submitters.submit(submit);
            Future<CompletableFuture<Integer>> b = submitters.submit(submit);
            CompletableFuture<Integer> first = a.get(5, TimeUnit.SECONDS);
            CompletableFuture<Integer> second = b.get(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(1, manager.getTask().size());
            assertEquals(1, (first.isCompletedExceptionally() ? 1 : 0) + (second.isCompletedExceptionally() ? 1 : 0));
            release.countDown();
            assertEquals(1, (first.handle((value, error) -> error == null ? 1 : 0).get(5, TimeUnit.SECONDS))
                    + second.handle((value, error) -> error == null ? 1 : 0).get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            submitters.shutdownNow();
        }
    }

    @Test
    void repeatableTasksCanRunTogether() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);
        try (TaskManager manager = new TaskManager(executor, failure -> fail(failure))) {
            var first = manager.submit(new RepeatableTask(release, started));
            var second = manager.submit(new RepeatableTask(release, started));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(2, manager.getTask().size());
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(1, second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationInterruptsWorkerAndCompletesOnEventThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean completionOnEdt = new AtomicBoolean();
        try (TaskManager manager = new TaskManager(executor, failure -> fail(failure))) {
            var result = manager.submit(new AbstractTask<Integer>("Interruptible") {
                @Override public Integer get() {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                        return 0;
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                        throw new CancellationException();
                    }
                }
            });
            var callback = result.handle((value, error) -> {
                completionOnEdt.set(SwingUtilities.isEventDispatchThread());
                return error;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertTrue(result.isDone());
            assertTrue(result.isCancelled());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, callback.get(5, TimeUnit.SECONDS));
            assertTrue(completionOnEdt.get());
            assertTrue(result.isCancelled());
        }
    }

    @Test
    void cancellationRetainsDuplicateReservationUntilWorkerExits() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        class StubbornTask extends AbstractTask<Integer> {
            StubbornTask() { super("Still exiting"); }
            @Override public Integer get() {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException expected) { interrupted.countDown(); }
                }
                return 1;
            }
        }
        try (TaskManager manager = new TaskManager(executor, failure -> {})) {
            var result = manager.submit(new StubbornTask());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            result.cancel(true);
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            var duplicate = manager.submit(new StubbornTask());
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class, () -> duplicate.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(1, manager.getTask().size());
        } finally {
            release.countDown();
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void closeCancelsQueuedTasksAndRejectsNewWork() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        TaskManager manager = new TaskManager(executor, failure -> {});
        var running = manager.submit(new BlockingTask(new CountDownLatch(1), started));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var queued = manager.submit(new RepeatableTask(new CountDownLatch(1), new CountDownLatch(0)));
        manager.close();
        manager.close();
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(running.isCancelled());
        assertTrue(queued.isCancelled());
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(manager.getTask().isEmpty());
        var rejected = manager.submit(new ImmediateTask());
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(ExecutionException.class, () -> rejected.get(5, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void shutdownDoesNotWaitForEventThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch eventThreadEntered = new CountDownLatch(1);
        CountDownLatch releaseEventThread = new CountDownLatch(1);
        TaskManager manager = new TaskManager(executor, failure -> {});
        var running = manager.submit(new BlockingTask(new CountDownLatch(1), started));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeLater(() -> {
                eventThreadEntered.countDown();
                try { releaseEventThread.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            assertTrue(eventThreadEntered.await(5, TimeUnit.SECONDS));
            closer.submit(manager::close).get(1, TimeUnit.SECONDS);
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        } finally {
            releaseEventThread.countDown();
            closer.shutdownNow();
            manager.close();
        }
        SwingUtilities.invokeAndWait(() -> {});
        assertTrue(running.isCancelled());
        assertTrue(manager.getTask().isEmpty());
    }

    @Test
    void dispatchRejectionCannotLeaveRegisteredTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        try (TaskManager manager = new TaskManager(executor, failure -> {})) {
            var result = manager.submit(new ImmediateTask());
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS)).getCause());
            assertTrue(manager.getTask().isEmpty());
        }
    }

    private static class ImmediateTask extends AbstractTask<Integer> {
        ImmediateTask() { super("Immediate"); }
        @Override public Integer get() { return 42; }
    }

    private static class BlockingTask extends AbstractTask<Integer> {
        private final CountDownLatch release;
        private final CountDownLatch started;
        BlockingTask(CountDownLatch release, CountDownLatch started) {
            super("Blocking");
            this.release = release;
            this.started = started;
        }
        @Override public Integer get() {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
            return 1;
        }
    }

    @Repeatable
    private static final class RepeatableTask extends BlockingTask {
        RepeatableTask(CountDownLatch release, CountDownLatch started) { super(release, started); }
    }

    private static final class InlineExecutor extends AbstractExecutorService {
        private boolean stopped;
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return List.of(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
        @Override public void execute(Runnable command) {
            if (stopped) throw new RejectedExecutionException();
            command.run();
        }
    }
}
