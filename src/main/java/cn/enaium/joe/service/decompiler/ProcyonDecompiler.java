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

package cn.enaium.joe.service.decompiler;

import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.config.util.CachedGlobalValue;
import com.strobel.assembler.InputTypeLoader;
import com.strobel.assembler.metadata.*;
import com.strobel.decompiler.DecompilationOptions;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import com.strobel.decompiler.languages.java.JavaFormattingOptions;

import java.io.StringWriter;
import java.util.Objects;

public class ProcyonDecompiler implements IDecompiler {
    public static final CachedGlobalValue<JavaFormattingOptions> cachedFormattingOptions = new CachedGlobalValue<>(config -> {
        JavaFormattingOptions formatting = JavaFormattingOptions.createDefault();
        config.getConfigMap().forEach((name, value) -> {
            try {
                JavaFormattingOptions.class.getField(name).set(formatting, value.getValue());
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("Invalid Procyon formatting option: " + name, error);
            }
        });
        return formatting;
    });

    private final Jar archive;
    private final JavaFormattingOptions formatting;

    public ProcyonDecompiler(Jar archive) {
        this(archive, JavaFormattingOptions.createDefault());
    }

    public ProcyonDecompiler(Jar archive, JavaFormattingOptions formatting) {
        this.archive = Objects.requireNonNull(archive, "Archive");
        this.formatting = formatting == null ? JavaFormattingOptions.createDefault() : formatting;
    }

    @Override
    public String decompile(ClassNode classNode) {
        InputTypeLoader runtimeLoader = new InputTypeLoader();
        MetadataSystem metadata = new MetadataSystem((name, buffer) -> {
            ClassNode node = archive.getClassNode(name);
            if (node == null) return runtimeLoader.tryLoadType(name, buffer);
            byte[] bytes = node.getClassBytes();
            buffer.reset(bytes.length);
            buffer.putByteArray(bytes, 0, bytes.length);
            buffer.position(0);
            return true;
        });
        TypeReference reference = metadata.lookupType(classNode.getInternalName());
        TypeDefinition type = reference == null ? null : reference.resolve();
        if (type == null) throw new IllegalStateException("Procyon could not resolve " + classNode.getInternalName() + " from the supplied archive");
        DecompilerSettings settings = DecompilerSettings.javaDefaults();
        settings.setJavaFormattingOptions(formatting);
        DecompilationOptions options = new DecompilationOptions();
        options.setFullDecompilation(true);
        options.setSettings(settings);
        StringWriter output = new StringWriter();
        settings.getLanguage().decompileType(type, new PlainTextOutput(output), options);
        if (output.toString().isBlank()) throw new IllegalStateException("Procyon produced no source for " + classNode.getInternalName());
        return output.toString();
    }
}
