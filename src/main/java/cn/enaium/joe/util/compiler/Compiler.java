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
            byte[] bytecode = compileApplicationSources(Map.of(canonicalName, text), Map.of()).get(canonicalName);
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
     * Compiles archive sources against platform APIs and only the supplied dependency bytecode.
     * Existing requested classes must share one target release, which is preserved with {@code --release}.
     * New classes use that release; when all requested classes are new, the supplied archive must have
     * one unambiguous release. An empty archive uses the running JDK release. Mixed requested targets,
     * ambiguous archive targets, preview bytecode and releases unsupported by javac fail explicitly.
     * Regenerated nested/additional classes must not raise the release of any existing output class.
     * Returns every generated class, including nested and additional top-level classes.
     * Neither the supplied sources nor dependency bytes are modified, even on failure.
     */
    public static Map<String, byte[]> compileSources(Map<String, String> sources,
                                                     Map<String, byte[]> classPath) throws CompileError {
        return compileSources(sources, classPath, null, false);
    }

    /**
     * Compiles editor visitors and transformers against explicit bytecode plus the application runtime
     * classpath, targeting the running JDK. Explicit dependencies take precedence over runtime classes.
     * Annotation processing is disabled and every generated class is returned.
     */
    public static Map<String, byte[]> compileApplicationSources(Map<String, String> sources,
                                                                Map<String, byte[]> classPath) throws CompileError {
        return compileSources(sources, classPath, null, true);
    }

    private static Map<String, byte[]> compileSources(Map<String, String> sources,
                                                      Map<String, byte[]> classPath,
                                                      DiagnosticListener<? super JavaFileObject> listener,
                                                      boolean application) throws CompileError {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new CompileError("Java compilation requires a full JDK. Launch JavaOctetEditor with JDK 21 or newer, not a JRE.");
        }
        if (sources.isEmpty()) throw new CompileError("No Java sources were supplied.");
        int release = application ? Runtime.version().feature() : archiveRelease(sources, classPath);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        DiagnosticListener<JavaFileObject> reporter = diagnostic -> {
            diagnostics.report(diagnostic);
            if (listener != null) listener.report(diagnostic);
        };
        StringWriter output = new StringWriter();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(reporter, Locale.ROOT, StandardCharsets.UTF_8)) {
            if (application) addRuntimeClassPath(standard);
            else standard.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            standard.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            try (VirtualFileManager manager = new VirtualFileManager(standard, classPath)) {
                List<JavaFileObject> units = new ArrayList<>(sources.size());
                sources.forEach((name, source) -> units.add(new VirtualJavaFileObject(name, Objects.requireNonNull(source, "Source for " + name))));
                JavaCompiler.CompilationTask task = compiler.getTask(output, manager, reporter,
                        List.of("-proc:none", "--release", Integer.toString(release)), null, units);
                if (!Boolean.TRUE.equals(task.call())) {
                    List<CompilerDiagnostic> reported = captureDiagnostics(diagnostics);
                    throw new CompileError(formatDiagnostics(reported, output.toString()), null, reported);
                }
                Map<String, byte[]> classes = manager.getClasses();
                if (!application) {
                    for (String name : classes.keySet()) {
                        if (classPath.containsKey(name)) {
                            int previous = classRelease(name, classPath.get(name));
                            if (previous < release) {
                                throw new CompileError("Cannot raise existing class target for " + name
                                        + " from Java " + previous + " to Java " + release
                                        + ". Compile classes with compatible targets together.");
                            }
                        }
                    }
                }
                return classes;
            }
        } catch (CompileError error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            List<CompilerDiagnostic> reported = captureDiagnostics(diagnostics);
            String details = formatDiagnostics(reported, output.toString());
            throw new CompileError("Java compilation failed for " + (application ? "application" : "archive")
                    + " target Java " + release + " (sources " + sources.keySet() + "): "
                    + error.getMessage() + "\n" + details, error, reported);
        }
    }

    private static int archiveRelease(Map<String, String> sources, Map<String, byte[]> classPath) throws CompileError {
        int release = 0;
        String first = null;
        for (String name : sources.keySet()) {
            if (!classPath.containsKey(name)) continue;
            int candidate = classRelease(name, classPath.get(name));
            if (release != 0 && release != candidate) throw mixedTargets(first, release, name, candidate);
            release = candidate;
            first = name;
        }
        if (release != 0) return release;
        for (Map.Entry<String, byte[]> entry : classPath.entrySet()) {
            int candidate = classRelease(entry.getKey(), entry.getValue());
            if (release != 0 && release != candidate) throw mixedTargets(first, release, entry.getKey(), candidate);
            release = candidate;
            first = entry.getKey();
        }
        return release == 0 ? Runtime.version().feature() : release;
    }

    private static CompileError mixedTargets(String first, int release, String name, int candidate) {
        return new CompileError("Cannot choose a safe archive target for mixed Java releases: " + first
                + " targets Java " + release + " but " + name + " targets Java " + candidate
                + ". Compile existing classes of one release together; add new classes with an existing class of the intended release.");
    }

    private static int classRelease(String name, byte[] bytes) throws CompileError {
        if (bytes == null || bytes.length < 8 || bytes[0] != (byte) 0xCA || bytes[1] != (byte) 0xFE
                || bytes[2] != (byte) 0xBA || bytes[3] != (byte) 0xBE) {
            throw new CompileError("Cannot determine archive target: invalid classfile for " + name + ".");
        }
        int minor = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        if (minor == 0xFFFF) {
            throw new CompileError("Cannot preserve preview classfile target for " + name
                    + ". Recompile without preview features before editing this archive.");
        }
        if (major < 45 || major > Runtime.version().feature() + 44 || (minor != 0 && !(major == 45 && minor == 3))) {
            throw new CompileError("Unsupported classfile target " + major + "." + minor + " for " + name
                    + ". Use a JDK that supports this archive's Java release.");
        }
        return major - 44;
    }

    private static List<CompilerDiagnostic> captureDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        List<Diagnostic<? extends JavaFileObject>> collected = diagnostics.getDiagnostics();
        List<CompilerDiagnostic> result = new ArrayList<>(collected.size());
        for (Diagnostic<? extends JavaFileObject> diagnostic : collected) {
            result.add(new CompilerDiagnostic(diagnostic.getKind(),
                    diagnostic.getSource() == null ? "" : diagnostic.getSource().getName(),
                    diagnostic.getLineNumber(), diagnostic.getColumnNumber(), diagnostic.getPosition(),
                    diagnostic.getMessage(Locale.ROOT)));
        }
        return result;
    }

    private static String formatDiagnostics(List<CompilerDiagnostic> diagnostics, String output) {
        StringBuilder message = new StringBuilder();
        for (CompilerDiagnostic diagnostic : diagnostics) {
            if (!diagnostic.source().isEmpty()) {
                message.append(diagnostic.source()).append(':')
                        .append(diagnostic.line()).append(':').append(diagnostic.column()).append(": ");
            }
            message.append(diagnostic.kind()).append(": ")
                    .append(diagnostic.message()).append('\n');
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

    /** Compiles collected application-extension sources against supplied and runtime dependencies. */
    public CompileError compile(Map<String, byte[]> classPath) {
        results = Map.of();
        try {
            results = compileSources(sources, classPath, listener, true);
            return null;
        } catch (CompileError error) {
            return error;
        }
    }

    public void setListener(DiagnosticListener<? super JavaFileObject> listener) {
        this.listener = listener;
    }
}
