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
import cn.enaium.joe.util.classes.JarHelper;
import cn.enaium.joe.util.config.util.CachedGlobalValue;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.Manifest;

/**
 * @author Enaium
 * @since 1.0.0
 */
public class VineFlowerDecompiler extends IFernflowerLogger implements IDecompiler, IResultSaver, IContextSource, IContextSource.IOutputSink {
    private volatile String returned;
    private final Jar archive;
    private final Map<String, Object> properties;
    private final JarLibrary library;
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private ClassNode toDecompileClass;
    private HashMap<String, ClassNode> classNodeHashMap;

    public static final CachedGlobalValue<Map<String, Object>> customProperties = new CachedGlobalValue<>(config -> {
        Map<String, String> map = new HashMap<>();
        config.getConfigMap().forEach((name, value) -> {
            if (value.getValue() != null) map.put(name, value.getValue().toString());
        });
        HashMap<String, Object> hashMap = new HashMap<>(map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String v = entry.getValue();
            if (v.equals("true")) {
                v = "1";
            } else if (v.equals("false")) {
                v = "0";
            }
            hashMap.put(entry.getKey(), v);
        }
        return Collections.unmodifiableMap(hashMap);
    });

    public VineFlowerDecompiler(Jar archive) {
        this(archive, Map.of());
    }

    public VineFlowerDecompiler(Jar archive, Map<String, Object> properties) {
        this.archive = Objects.requireNonNull(archive, "Archive");
        this.library = new JarLibrary(archive);
        this.properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    @Override
    public synchronized String decompile(final ClassNode classNode) {
        toDecompileClass = classNode;
        classNodeHashMap = JarHelper.getAllNodes(archive, classNode);
        returned = null;
        failures.clear();
        BaseDecompiler baseDecompiler = new BaseDecompiler(this, properties, this);
        baseDecompiler.addSource(this);
        baseDecompiler.addLibrary(library);
        baseDecompiler.decompileContext();
        if (!failures.isEmpty()) throw new IllegalStateException("VineFlower could not decompile " + classNode.getInternalName() + ": " + String.join("; ", failures));
        if (returned == null || returned.isBlank()) throw new IllegalStateException("VineFlower produced no source for " + classNode.getInternalName());
        return returned;
    }

    @Override
    public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
        if (returned == null && toDecompileClass.getInternalName().equals(qualifiedName)) {
            returned = content;
        }
    }

    @Override
    public void writeMessage(String message, Throwable t) {
        failures.add(message + ": " + t);
    }

    @Override
    public void writeMessage(String message, Severity severity) {
        if (severity == Severity.ERROR) failures.add(message);
    }

    @Override
    public void writeMessage(String message, Severity severity, Throwable t) {
        if (severity == Severity.ERROR) failures.add(message + ": " + t);
    }

    @Override
    public String getName() {
        return "JavaOctetEditor source";
    }

    @Override
    public Entries getEntries() {
        return new Entries(classNodeHashMap.keySet().stream().map(Entry::atBase).toList(), List.of(), List.of());
    }

    @Override
    public boolean isLazy() {
        return true;
    }

    @Override
    public InputStream getInputStream(String resource) throws IOException {
        return library.getInputStream(resource);
    }

    @Override
    public byte[] getClassBytes(String className) {
        if (hasClass(className)) return classNodeHashMap.get(className).getClassBytes();
        return null;
    }

    @Override
    public boolean hasClass(String className) {
        return classNodeHashMap.containsKey(className);
    }

    @Override
    public IOutputSink createOutputSink(IResultSaver saver) {
        return this;
    }

    @Override
    public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
        this.saveClassFile(null, qualifiedName, null, content, mapping);
    }

    @Override
    public void begin() {
    }

    @Override
    public void close() {
    }

    @Override
    public void acceptDirectory(String s) {
    }

    @Override
    public void acceptOther(String s) {
    }

    @Override
    public void saveFolder(String path) {
    }

    @Override
    public void copyFile(String source, String path, String entryName) {
    }

    @Override
    public void createArchive(String path, String archiveName, Manifest manifest) {
    }

    @Override
    public void saveDirEntry(String path, String archiveName, String entryName) {
    }

    @Override
    public void copyEntry(String source, String path, String archiveName, String entry) {
    }

    @Override
    public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {
    }

    @Override
    public void closeArchive(String path, String archiveName) {
    }

    public static class JarLibrary implements IContextSource {
        private final Jar jar;

        public JarLibrary(Jar jar) {
            this.jar = jar;
        }

        @Override
        public String getName() {
            return "JavaOctetEditor Jar Library";
        }

        @Override
        public Entries getEntries() {
            return new Entries(jar.getClasses().stream().map(ClassNode::getInternalName).map(Entry::atBase).toList(), List.of(), List.of());
        }

        @Override
        public InputStream getInputStream(String resource) {
            if (resource.endsWith(".class")) {
                ClassNode node = jar.getClassNode(resource.substring(0, resource.length() - 6));
                if (node != null) return new ByteArrayInputStream(node.getClassBytes());
            }
            byte[] bytes = jar.getResource(resource);
            return bytes == null ? ClassLoader.getSystemResourceAsStream(resource) : new ByteArrayInputStream(bytes);
        }

        @Override
        public byte[] getClassBytes(String className) throws IOException {
            ClassNode node = jar.getClassNode(className);
            if (node != null) return node.getClassBytes();
            try (InputStream input = ClassLoader.getSystemResourceAsStream(className + ".class")) {
                return input == null ? null : input.readAllBytes();
            }
        }

        @Override
        public boolean hasClass(String className) {
            return jar.hasClass(className) || ClassLoader.getSystemResource(className + ".class") != null;
        }
    }
}