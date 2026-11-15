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

    /** Captures desktop options now; bind an independent archive on the worker thread. */
    public static Function<Jar, IDecompiler> captureFactory() {
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        Service backend = editor.CONFIG.getByClass(ApplicationConfig.class).decompilerMode.getValue();
        Function<Jar, IDecompiler> factory = switch (backend) {
            case VineFlower -> {
                var properties = VineFlowerDecompiler.customProperties.getValue();
                yield archive -> new VineFlowerDecompiler(archive, properties);
            }
            case CFR -> {
                var options = CFRDecompiler.options.getValue();
                yield archive -> new CFRDecompiler(archive, options);
            }
            case Procyon -> {
                var formatting = ProcyonDecompiler.cachedFormattingOptions.getValue();
                yield archive -> new ProcyonDecompiler(archive, formatting);
            }
        };
        return archive -> {
            IDecompiler decompiler = factory.apply(Objects.requireNonNull(archive, "Archive"));
            return node -> invoke(decompiler, node, backend);
        };
    }
}
