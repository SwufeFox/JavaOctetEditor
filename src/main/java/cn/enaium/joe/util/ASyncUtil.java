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

package cn.enaium.joe.util;

import javax.swing.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Enaium
 */
public final class ASyncUtil {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "joe-background");
        thread.setDaemon(true);
        return thread;
    });

    private ASyncUtil() {
    }

    public static void execute(Runnable run) {
        execute(run, null);
    }

    public static void execute(Runnable run, Runnable ui) {
        EXECUTOR.execute(() -> {
            try {
                run.run();
                if (ui != null && !Thread.currentThread().isInterrupted() && !EXECUTOR.isShutdown()) {
                    SwingUtilities.invokeLater(() -> {
                        if (!EXECUTOR.isShutdown()) ui.run();
                    });
                }
            } catch (java.util.concurrent.CancellationException ignored) {
                // Closing the application cancels background work.
            } catch (Throwable failure) {
                MessageUtil.error(failure);
            }
        });
    }

    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
