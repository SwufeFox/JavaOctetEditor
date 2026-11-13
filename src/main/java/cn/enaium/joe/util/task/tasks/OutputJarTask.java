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

import cn.enaium.joe.jar.ArchiveIO;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.task.AbstractTask;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * @author Enaium
 * @since 0.10.0
 */
public class OutputJarTask extends AbstractTask<Boolean> {
    private final Jar jar;
    private final File out;

    public OutputJarTask(Jar jar, File out) {
        super("OutputJar");
        this.jar = cn.enaium.joe.jar.ArchiveSnapshot.capture(jar).toJar();
        this.out = out;
    }

    @Override
    public Boolean get() {
        try {
            ArchiveIO.write(jar, out.toPath(), this::setProgress);
            return true;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not export archive: " + out, exception);
        }
    }
}
