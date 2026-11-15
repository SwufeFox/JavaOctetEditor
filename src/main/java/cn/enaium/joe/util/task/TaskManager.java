/*
 * Copyright 2022 Enaium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.enaium.joe.util.task;

import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.Pair;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * @author Enaium
 * @since 0.10.0
 */
public class TaskManager implements AutoCloseable {
    private final ExecutorService executorService;
    private final Consumer<Throwable> errorHandler;
    private final List<Entry<?>> tasks = new ArrayList<>();
    private volatile boolean closed;

    public TaskManager() {
        this(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "joe-task");
            thread.setDaemon(true);
            return thread;
        }), MessageUtil::error);
    }

    TaskManager(ExecutorService executorService, Consumer<Throwable> errorHandler) {
        this.executorService = Objects.requireNonNull(executorService);
        this.errorHandler = Objects.requireNonNull(errorHandler);
    }

    public <T> CompletableFuture<T> submit(AbstractTask<T> task) {
        return submit(task, errorHandler);
    }

    /** Reports non-cancellation failures once on EDT, after completing the task future. */
    public synchronized <T> CompletableFuture<T> submit(AbstractTask<T> task, Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(task);
        Entry<T> entry = new Entry<>(task, Objects.requireNonNull(failureHandler));
        if (closed) {
            entry.finish(null, new RejectedExecutionException("Task manager is closed"));
            return entry.result;
        }
        if (!task.getClass().isAnnotationPresent(Repeatable.class)
                && tasks.stream().anyMatch(it -> it.task.getClass() == task.getClass())) {
            entry.finish(null, new IllegalStateException("Task already running: " + task.getName()));
            return entry.result;
        }
        // Registration and duplicate detection are one operation, before dispatch.
        tasks.add(entry);
        try {
            executorService.execute(entry.worker);
        } catch (RejectedExecutionException failure) {
            tasks.remove(entry);
            entry.finish(null, failure);
        }
        return entry.result;
    }

    public synchronized List<Pair<AbstractTask<?>, CompletableFuture<?>>> getTask() {
        List<Pair<AbstractTask<?>, CompletableFuture<?>>> snapshot = new ArrayList<>(tasks.size());
        for (Entry<?> entry : tasks) {
            snapshot.add(new Pair<>(entry.task, entry.result));
        }
        return List.copyOf(snapshot);
    }

    public void cancelAll() {
        List<Entry<?>> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(tasks);
        }
        for (Entry<?> entry : snapshot) entry.cancel(true);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        cancelAll();
        executorService.shutdownNow();
    }

    private final class Entry<T> {
        private final AbstractTask<T> task;
        private final Consumer<Throwable> failureHandler;
        private final CompletableFuture<T> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (SwingUtilities.isEventDispatchThread()) return Entry.this.cancel(mayInterruptIfRunning);
                FutureTask<Boolean> request = new FutureTask<>(() -> Entry.this.cancel(mayInterruptIfRunning));
                SwingUtilities.invokeLater(request);
                boolean interrupted = false;
                try {
                    for (;;) {
                        try {
                            return request.get();
                        } catch (InterruptedException failure) {
                            interrupted = true;
                        } catch (ExecutionException failure) {
                            throw new CompletionException(failure.getCause());
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        };
        private final FutureTask<T> worker;
        private boolean running;

        private Entry(AbstractTask<T> task, Consumer<Throwable> failureHandler) {
            this.task = task;
            this.failureHandler = failureHandler;
            worker = new FutureTask<>(task::get) {
                @Override
                public void run() {
                    synchronized (TaskManager.this) {
                        if (isCancelled()) return;
                        running = true;
                    }
                    try {
                        super.run();
                    } finally {
                        synchronized (TaskManager.this) {
                            tasks.remove(Entry.this);
                        }
                    }
                }

                @Override
                protected void done() {
                    synchronized (TaskManager.this) {
                        if (!running || !isCancelled()) tasks.remove(Entry.this);
                    }
                    try {
                        finish(get(), null);
                    } catch (CancellationException failure) {
                        finish(null, failure);
                    } catch (ExecutionException failure) {
                        finish(null, failure.getCause());
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        finish(null, failure);
                    }
                }
            };
        }

        private boolean cancel(boolean mayInterruptIfRunning) {
            if (result.isDone()) return false;
            task.cancel();
            worker.cancel(mayInterruptIfRunning);
            finish(null, new CancellationException("Task cancelled"));
            return true;
        }

        private void finish(T value, Throwable failure) {
            Runnable completion = () -> {
                if (result.isDone()) return;
                Throwable error = failure;
                try {
                    if (error == null) {
                        synchronized (TaskManager.this) {
                            if (closed) task.cancel();
                        }
                        task.checkCancelled();
                        task.onSuccess(value);
                    }
                } catch (Throwable publicationFailure) {
                    error = publicationFailure;
                }
                if (error == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(error);
                    if (!(error instanceof CancellationException) && !closed) {
                        try {
                            failureHandler.accept(error);
                        } catch (Throwable reportingFailure) {
                            if (reportingFailure != error) reportingFailure.addSuppressed(error);
                            errorHandler.accept(reportingFailure);
                        }
                    }
                }
            };
            if (failure instanceof CancellationException && SwingUtilities.isEventDispatchThread()) completion.run();
            else SwingUtilities.invokeLater(completion);
        }
    }
}
