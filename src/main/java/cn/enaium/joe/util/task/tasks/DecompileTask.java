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
import cn.enaium.joe.service.decompiler.IDecompiler;
import cn.enaium.joe.service.DecompileService;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.AbstractTask;
import cn.enaium.joe.util.task.Repeatable;
import org.tinylog.Logger;

import java.util.Objects;
import java.util.function.Function;

/**
 * @author Enaium
 * @since 0.10.0
 */
@Repeatable
public class DecompileTask extends AbstractTask<String> {

    private final ArchiveSnapshot baseline;
    private final String internalName;
    private final Function<Jar, IDecompiler> decompilerFactory;

    public DecompileTask(ArchiveSnapshot baseline, String internalName) {
        super("Decompile " + internalName);
        this.baseline = Objects.requireNonNull(baseline);
        this.internalName = Objects.requireNonNull(internalName);
        this.decompilerFactory = DecompileService.captureFactory();
    }

    @Override
    public String get() {
        checkCancelled();
        Jar archive = baseline.toJar();
        ClassNode classNode = Objects.requireNonNull(archive.getClassNode(internalName), "Class not found: " + internalName);
        IDecompiler decompiler = decompilerFactory.apply(archive);
        checkCancelled();
        Logger.info("DECOMPILE:{}", internalName);
        String source = decompiler.decompile(classNode);
        setProgress(100);
        return source;
    }
}
