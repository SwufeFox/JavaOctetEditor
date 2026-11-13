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
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class CFRDecompiler implements IDecompiler {
    public static final CachedGlobalValue<Map<String, String>> options = new CachedGlobalValue<>(config -> {
        Map<String, String> values = new HashMap<>();
        config.getConfigMap().forEach((name, value) -> {
            if (value.getValue() != null) values.put(name, value.getValue().toString());
        });
        return Map.copyOf(values);
    });

    private final Jar archive;
    private final Map<String, String> settings;

    public CFRDecompiler(Jar archive) {
        this(archive, Map.of());
    }

    public CFRDecompiler(Jar archive, Map<String, String> settings) {
        this.archive = Objects.requireNonNull(archive, "Archive");
        this.settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    @Override
    public String decompile(ClassNode classNode) {
        StringBuilder source = new StringBuilder();
        List<String> failures = new ArrayList<>();
        ClassFileSource files = new ClassFileSource() {
            @Override
            public Pair<byte[], String> getClassFileContent(String path) throws IOException {
                String name = path.endsWith(".class") ? path.substring(0, path.length() - 6) : path;
                ClassNode node = archive.getClassNode(name);
                if (node != null) return Pair.make(node.getClassBytes(), path);
                try (InputStream input = ClassLoader.getSystemResourceAsStream(path)) {
                    if (input != null) return Pair.make(input.readAllBytes(), path);
                }
                throw new IOException("Class dependency not found in archive or runtime: " + path);
            }

            @Override public void informAnalysisRelativePathDetail(String usePath, String classFilePath) { }
            @Override public Collection<String> addJar(String jarPath) { return List.of(); }
            @Override public String getPossiblyRenamedPath(String path) { return path; }
        };
        OutputSinkFactory sink = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType type, Collection<SinkClass> available) {
                if (type == SinkType.JAVA && available.contains(SinkClass.DECOMPILED)) return List.of(SinkClass.DECOMPILED);
                if (type == SinkType.EXCEPTION && available.contains(SinkClass.EXCEPTION_MESSAGE)) return List.of(SinkClass.EXCEPTION_MESSAGE);
                return List.of(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType type, SinkClass sinkClass) {
                return value -> {
                    if (type == SinkType.JAVA) {
                        if (value instanceof SinkReturns.Decompiled decompiled) source.append(decompiled.getJava());
                        else source.append(value);
                    } else if (type == SinkType.EXCEPTION) {
                        if (value instanceof SinkReturns.ExceptionMessage failure) {
                            failures.add(failure.getPath() + ": " + failure.getMessage() + ": " + failure.getThrownException());
                        } else failures.add(String.valueOf(value));
                    }
                };
            }
        };
        Map<String, String> effectiveSettings = new HashMap<>(settings);
        effectiveSettings.remove("outputdir");
        effectiveSettings.remove("outputpath");
        effectiveSettings.put("silent", "true");
        new CfrDriver.Builder().withOptions(effectiveSettings).withClassFileSource(files)
                .withOutputSink(sink).build().analyse(List.of(classNode.getInternalName() + ".class"));
        if (!failures.isEmpty()) throw new IllegalStateException("CFR could not decompile " + classNode.getInternalName() + ": " + String.join("; ", failures));
        if (source.isEmpty()) throw new IllegalStateException("CFR produced no source for " + classNode.getInternalName());
        return source.toString();
    }
}
