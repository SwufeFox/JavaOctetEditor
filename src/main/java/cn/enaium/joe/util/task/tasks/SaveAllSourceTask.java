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

import cn.enaium.joe.jar.ArchiveSnapshot;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.service.DecompileService;
import cn.enaium.joe.service.decompiler.IDecompiler;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.AbstractTask;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author Enaium
 * @since 0.10.0
 */
public class SaveAllSourceTask extends AbstractTask<Boolean> {
    private final ArchiveSnapshot baseline;
    private final File out;
    private final Function<Jar, IDecompiler> decompilerFactory;

    public SaveAllSourceTask(Jar jar, File out) {
        super("SaveAllSource");
        this.baseline = ArchiveSnapshot.capture(jar);
        this.decompilerFactory = DecompileService.captureFactory();
        this.out = out;
    }

    @Override
    public Boolean get() {
        checkCancelled();
        Jar jar = baseline.toJar();
        IDecompiler decompiler = decompilerFactory.apply(jar);
        int completed = 0;
        int count = jar.getClassSize() + jar.getResourceSize();
        Path destination = out.toPath().toAbsolutePath();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(destination.getParent(), ".joe-source-", ".zip");
            try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (ClassNode node : jar.getClasses()) {
                    setProgress(completed++ * 100 / Math.max(1, count));
                    if (node.getOuterClassInternalName() != null || node.getNodeInternal().innerClasses.stream()
                            .anyMatch(inner -> node.getInternalName().equals(inner.name) && inner.outerName != null)) continue;
                    stream.putNextEntry(new ZipEntry("src/main/java/" + node.getInternalName() + ".java"));
                    stream.write(decompiler.decompile(node).getBytes(StandardCharsets.UTF_8));
                    stream.closeEntry();
                }
                for (Map.Entry<String, byte[]> entry : jar.getResources().entrySet()) {
                    setProgress(completed++ * 100 / Math.max(1, count));
                    stream.putNextEntry(new ZipEntry("src/main/resources/" + entry.getKey()));
                    stream.write(entry.getValue());
                    stream.closeEntry();
                }
            }
            setProgress(100);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            return true;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not export sources to " + destination, failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException failure) { temporary.toFile().deleteOnExit(); }
            }
        }
    }
}
