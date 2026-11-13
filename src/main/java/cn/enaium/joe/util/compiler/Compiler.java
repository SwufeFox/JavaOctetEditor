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

package cn.enaium.joe.util.compiler;

import cn.enaium.joe.util.compiler.virtual.VirtualFileManager;
import cn.enaium.joe.util.compiler.virtual.VirtualJavaFileObject;

import javax.tools.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** In-memory Java compilation. Names use binary dot notation, including '$' for nested classes. */
public class Compiler {
    private final Map<String, String> sources = new LinkedHashMap<>();
    private Map<String, byte[]> results = Map.of();
    private DiagnosticListener<? super JavaFileObject> listener;

    public void addSource(String name, String content) {
        sources.put(name, content);
    }

    public Map<String, byte[]> getClasses() {
        return results;
    }

    /** Compiles against the running application's classpath, for ASM visitors and transformers. */
    public static byte[] compileSingle(String canonicalName, String text) {
        return compileSingle(canonicalName, text, null);
    }

    public static byte[] compileSingle(String canonicalName, String text, Writer errorTracer) {
        try {
            byte[] bytecode = compileSources(Map.of(canonicalName, text), Map.of()).get(canonicalName);
            if (bytecode == null) throw new CompileError("Compilation did not produce the requested class: " + canonicalName);
            return bytecode;
        } catch (CompileError error) {
            if (errorTracer != null) {
                PrintWriter writer = new PrintWriter(errorTracer);
                writer.println(error.getMessage());
                writer.flush();
            }
            return null;
        }
    }

    /**
     * Compiles all sources together, overlaying the running classpath with supplied bytecode.
     * Returns every generated class, including nested and additional top-level classes.
     * Neither the supplied sources nor dependency bytes are modified, even on failure.
     */
    public static Map<String, byte[]> compileSources(Map<String, String> sources,
                                                     Map<String, byte[]> classPath) throws CompileError {
        return compileSources(sources, classPath, null);
    }

    private static Map<String, byte[]> compileSources(Map<String, String> sources,
                                                      Map<String, byte[]> classPath,
                                                      DiagnosticListener<? super JavaFileObject> listener) throws CompileError {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new CompileError("Java compilation requires a full JDK. Launch JavaOctetEditor with JDK 21 or newer, not a JRE.");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        DiagnosticListener<JavaFileObject> reporter = diagnostic -> {
            diagnostics.report(diagnostic);
            if (listener != null) listener.report(diagnostic);
        };
        StringWriter output = new StringWriter();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(reporter, Locale.ROOT, StandardCharsets.UTF_8)) {
            addRuntimeClassPath(standard);
            try (VirtualFileManager manager = new VirtualFileManager(standard, classPath)) {
                if (sources.isEmpty()) throw new CompileError("No Java sources were supplied.");
                List<JavaFileObject> units = new ArrayList<>(sources.size());
                sources.forEach((name, source) -> units.add(new VirtualJavaFileObject(name, Objects.requireNonNull(source, "Source for " + name))));
                JavaCompiler.CompilationTask task = compiler.getTask(output, manager, reporter,
                        List.of("-proc:none"), null, units);
                if (!Boolean.TRUE.equals(task.call())) {
                    throw new CompileError(formatDiagnostics(diagnostics, output.toString()));
                }
                return manager.getClasses();
            }
        } catch (CompileError error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            String details = formatDiagnostics(diagnostics, output.toString());
            throw new CompileError("Java compilation failed: " + error.getMessage() + "\n" + details, error);
        }
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics, String output) {
        StringBuilder message = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getSource() != null) {
                message.append(diagnostic.getSource().getName()).append(':')
                        .append(diagnostic.getLineNumber()).append(':').append(diagnostic.getColumnNumber()).append(": ");
            }
            message.append(diagnostic.getKind()).append(": ")
                    .append(diagnostic.getMessage(Locale.ROOT)).append('\n');
        }
        if (!output.isBlank()) message.append(output);
        return message.isEmpty() ? "The Java compiler failed without diagnostics." : message.toString().stripTrailing();
    }

    public CompileError compile() {
        return compile(Map.of());
    }

    private static void addRuntimeClassPath(StandardJavaFileManager manager) throws IOException {
        Set<Path> paths = new LinkedHashSet<>();
        Iterable<? extends Path> defaults = manager.getLocationAsPaths(StandardLocation.CLASS_PATH);
        if (defaults != null) defaults.forEach(paths::add);
        for (ClassLoader loader = Compiler.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) {
                    if (url.getProtocol().equals("file")) {
                        try {
                            paths.add(Path.of(url.toURI()));
                        } catch (URISyntaxException error) {
                            throw new IOException("Invalid compiler classpath entry: " + url, error);
                        }
                    }
                }
            }
        }
        manager.setLocationFromPaths(StandardLocation.CLASS_PATH, paths);
    }

    public CompileError compile(Map<String, byte[]> classPath) {
        results = Map.of();
        try {
            results = compileSources(sources, classPath, listener);
            return null;
        } catch (CompileError error) {
            return error;
        }
    }

    public void setListener(DiagnosticListener<? super JavaFileObject> listener) {
        this.listener = listener;
    }
}
