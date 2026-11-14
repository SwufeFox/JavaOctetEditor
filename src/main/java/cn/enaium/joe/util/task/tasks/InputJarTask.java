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
import cn.enaium.joe.util.task.AbstractTask;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.BooleanSupplier;

/**
 * @author Enaium
 * @since 0.10.0
 */
@cn.enaium.joe.util.task.Repeatable
public class InputJarTask extends AbstractTask<Jar> {
    private final File file;
    private final JavaOctetEditor editor;
    private final long request;
    private final BooleanSupplier publicationAllowed;

    public InputJarTask(File file, long request, BooleanSupplier publicationAllowed) {
        super("InputJar");
        this.file = file;
        this.editor = JavaOctetEditor.getInstance();
        this.request = request;
        this.publicationAllowed = publicationAllowed;
    }

    @Override
    public Jar get() {
        Logger.info("LOAD:{}", file.getAbsolutePath());
        try {
            return ArchiveIO.read(file.toPath(), this::setProgress);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not input archive: " + file, exception);
        }
    }

    @Override
    protected void onSuccess(Jar jar) {
        if (!editor.isArchiveLoadCurrent(request)) {
            throw new java.util.concurrent.CancellationException("A newer archive load replaced this request");
        }
        if (!publicationAllowed.getAsBoolean()) {
            editor.notifyStatus(cn.enaium.joe.util.LangUtil.i18n("document.loadChanged"));
            throw new java.util.concurrent.CancellationException("The document changed while the archive was loading");
        }
        editor.acceptArchiveLoad(file, jar);
    }
}
