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

package cn.enaium.joe.service;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.service.decompiler.IDecompiler;
import cn.enaium.joe.service.decompiler.VineFlowerDecompiler;
import cn.enaium.joe.service.decompiler.CFRDecompiler;
import cn.enaium.joe.service.decompiler.ProcyonDecompiler;

import java.util.Objects;
import java.util.function.Function;

/**
 * @author Enaium
 * @since 0.7.0
 */
public class DecompileService {
    public enum Service{
        VineFlower(VineFlowerDecompiler::new),
        CFR(CFRDecompiler::new),
        Procyon(ProcyonDecompiler::new);


        private final Function<Jar, IDecompiler> decompilerFactory;
        Service(Function<Jar, IDecompiler> factory){
            this.decompilerFactory = factory;
        }

        public IDecompiler getDecompiler(Jar archive){
            return decompilerFactory.apply(archive);
        }
    }

    /** Decompiles using only the supplied archive and backend defaults, without desktop initialization. */
    public static String decompile(Jar archive, String internalName, Service backend) {
        Objects.requireNonNull(archive, "Archive");
        Objects.requireNonNull(backend, "Decompiler backend");
        ClassNode node = archive.getClassNode(Objects.requireNonNull(internalName, "Class internal name"));
        if (node == null) throw new IllegalArgumentException("Class not found in archive: " + internalName);
        return invoke(backend.getDecompiler(archive), node, backend);
    }

    private static String invoke(IDecompiler decompiler, ClassNode node, Service backend) {
        try {
            String source = decompiler.decompile(node);
            if (source == null || source.isBlank()) throw new IllegalStateException("The backend produced no source");
            return source;
        } catch (RuntimeException error) {
            throw new IllegalStateException(backend + " could not decompile " + node.getInternalName() + ": " + error.getMessage(), error);
        }
    }


    public static IDecompiler getService() {
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        if (editor == null || editor.getJar() == null) throw new IllegalStateException("Open an archive before decompiling");
        Jar archive = editor.getJar();
        Service backend = editor.CONFIG.getByClass(ApplicationConfig.class).decompilerMode.getValue();
        IDecompiler decompiler = switch (backend) {
            case VineFlower -> new VineFlowerDecompiler(archive, VineFlowerDecompiler.customProperties.getValue());
            case CFR -> new CFRDecompiler(archive, CFRDecompiler.options.getValue());
            case Procyon -> new ProcyonDecompiler(archive, ProcyonDecompiler.cachedFormattingOptions.getValue());
        };
        return node -> invoke(decompiler, node, backend);
    }
}
