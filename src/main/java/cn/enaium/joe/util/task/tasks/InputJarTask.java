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

package cn.enaium.joe.util.task.tasks;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.jar.ArchiveIO;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.util.task.AbstractTask;
import org.tinylog.Logger;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * @author Enaium
 * @since 0.10.0
 */
public class InputJarTask extends AbstractTask<Jar> {
    private final File file;

    public InputJarTask(File file) {
        super("InputJar");
        this.file = file;
    }

    @Override
    public Jar get() {
        Logger.info("LOAD:{}", file.getAbsolutePath());
        try {
            Jar jar = ArchiveIO.read(file.toPath(), this::setProgress);
            publish(jar);
            return jar;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not input archive: " + file, exception);
        }
    }

    private void publish(Jar jar) throws IOException {
        Thread worker = Thread.currentThread();
        Runnable update = () -> {
            if (worker.isInterrupted()) throw new CancellationException("Archive input interrupted");
            JavaOctetEditor editor = JavaOctetEditor.getInstance();
            editor.setJar(jar);
            var recent = editor.CONFIG.getByClass(ApplicationConfig.class).loadRecent.getValue();
            String path = file.getAbsolutePath();
            recent.remove(path);
            recent.add(path);
            editor.window.setTitle(JavaOctetEditor.TITLE + "-" + file.getName());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
            return;
        }
        FutureTask<Void> publication = new FutureTask<>(update, null);
        SwingUtilities.invokeLater(publication);
        try {
            publication.get();
        } catch (InterruptedException exception) {
            publication.cancel(false);
            worker.interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Archive input interrupted before publication");
            interrupted.initCause(exception);
            throw interrupted;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException failure) throw failure;
            if (cause instanceof Error failure) throw failure;
            throw new IOException("Could not publish archive", cause);
        }
    }
}
