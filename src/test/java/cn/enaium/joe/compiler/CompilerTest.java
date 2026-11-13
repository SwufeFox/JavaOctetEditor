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

package cn.enaium.joe.compiler;

import cn.enaium.joe.util.compiler.CompileError;
import cn.enaium.joe.util.compiler.Compiler;
import cn.enaium.joe.util.compiler.virtual.MemoryClassLoader;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompilerTest {
    @Test
    void singleClassFailureWritesActionableDiagnostics() {
        StringWriter errors = new StringWriter();
        assertNull(Compiler.compileSingle("Invalid", "public class Invalid { int value() { return unknown; } }", errors));
        assertTrue(errors.toString().contains("Invalid.java"), errors.toString());
        assertTrue(errors.toString().contains("unknown"), errors.toString());
        StringWriter missingOutput = new StringWriter();
        assertNull(Compiler.compileSingle("Requested", "class Different { }", missingOutput));
        assertTrue(missingOutput.toString().contains("Requested"), missingOutput.toString());
    }

    @Test
    void compilesAndInvokesSingleClass() throws Exception {
        StringWriter errors = new StringWriter();
        byte[] bytes = Compiler.compileSingle("CompilerTestFooClass",
                "public class CompilerTestFooClass { public static boolean foo() { return CompilerTestFooClass.class.getName().equals(\"CompilerTestFooClass\"); } }", errors);
        assertNotNull(bytes, errors.toString());
        Class<?> compiled = new MemoryClassLoader(getClass().getClassLoader(), Map.of("CompilerTestFooClass", bytes))
                .loadClass("CompilerTestFooClass");
        assertEquals(true, compiled.getMethod("foo").invoke(null));
    }

    @Test
    void resolvesArchiveBytecodeAndNestedDependencies() throws Exception {
        Map<String, byte[]> dependencies = Compiler.compileSources(Map.of("archive.Dependency", """
                package archive;
                public class Dependency {
                    public static int value() { return 40; }
                    public static class Nested { public static int value() { return 2; } }
                }
                """), Map.of());
        byte[] dependencyBefore = dependencies.get("archive.Dependency").clone();
        Map<String, byte[]> output = Compiler.compileSources(Map.of("client.Consumer", """
                package client;
                import archive.Dependency;
                public class Consumer {
                    public static int answer() { return Dependency.value() + Dependency.Nested.value(); }
                }
                """), dependencies);
        assertEquals(1, output.size());
        assertArrayEquals(dependencyBefore, dependencies.get("archive.Dependency"));
        Map<String, byte[]> classes = new LinkedHashMap<>(dependencies);
        classes.putAll(output);
        Class<?> compiled = new MemoryClassLoader(getClass().getClassLoader(), classes).loadClass("client.Consumer");
        assertEquals(42, compiled.getMethod("answer").invoke(null));
    }

    @Test
    void resolvesDefaultPackageDependencies() throws Exception {
        Map<String, byte[]> dependencies = Compiler.compileSources(Map.of("DefaultDependency",
                "public class DefaultDependency { public static int value() { return 42; } }"), Map.of());
        Map<String, byte[]> output = Compiler.compileSources(Map.of("DefaultConsumer",
                "public class DefaultConsumer { public static int answer() { return DefaultDependency.value(); } }"), dependencies);
        Map<String, byte[]> classes = new LinkedHashMap<>(dependencies);
        classes.putAll(output);
        assertEquals(42, new MemoryClassLoader(getClass().getClassLoader(), classes)
                .loadClass("DefaultConsumer").getMethod("answer").invoke(null));
    }

    @Test
    void returnsNestedAndAdditionalTopLevelClassesAcrossSources() throws Exception {
        Map<String, byte[]> classes = Compiler.compileSources(Map.of(
                "multiple.Outer", """
                    package multiple;
                    public class Outer {
                        public static class Inner { public int value() { return Helper.value(); } }
                        public static int answer() { return new Inner().value() + Other.value(); }
                    }
                    class Helper { static int value() { return 40; } }
                    """,
                "multiple.Other", "package multiple; public class Other { static int value() { return 2; } }"), Map.of());
        assertEquals(java.util.Set.of("multiple.Outer", "multiple.Outer$Inner", "multiple.Helper", "multiple.Other"), classes.keySet());
        assertEquals(42, new MemoryClassLoader(getClass().getClassLoader(), classes)
                .loadClass("multiple.Outer").getMethod("answer").invoke(null));
    }

    @Test
    void reportsLocationsAndClearsPriorResultsOnFailure() throws Exception {
        Compiler compiler = new Compiler();
        List<Diagnostic.Kind> diagnostics = new ArrayList<>();
        compiler.setListener(diagnostic -> diagnostics.add(diagnostic.getKind()));
        compiler.addSource("Broken", "public class Broken { }");
        assertNull(compiler.compile());
        assertFalse(compiler.getClasses().isEmpty());
        compiler.addSource("Broken", """
                public class Broken {
                    public int value() { return missingSymbol; }
                }
                """);
        CompileError error = compiler.compile();
        assertNotNull(error);
        assertTrue(error.getMessage().contains("Broken.java:2:"), error.getMessage());
        assertTrue(error.getMessage().contains("missingSymbol"), error.getMessage());
        assertTrue(diagnostics.contains(Diagnostic.Kind.ERROR));
        assertTrue(compiler.getClasses().isEmpty());
        compiler.addSource("Broken", "public class Broken { public static int value() { return 42; } }");
        assertNull(compiler.compile());
        assertEquals(42, new MemoryClassLoader(getClass().getClassLoader(), compiler.getClasses())
                .loadClass("Broken").getMethod("value").invoke(null));
    }

    @Test
    void failedCompilationLeavesDependencyBytesUntouched() throws Exception {
        Map<String, byte[]> dependencies = Compiler.compileSources(Map.of("Dependency",
                "public class Dependency { public static int value() { return 42; } }"), Map.of());
        byte[] before = dependencies.get("Dependency").clone();
        CompileError error = assertThrows(CompileError.class, () -> Compiler.compileSources(Map.of("Consumer",
                "public class Consumer { int value() { return Dependency.missing(); } }"), dependencies));
        assertTrue(error.getMessage().contains("missing"), error.getMessage());
        assertArrayEquals(before, dependencies.get("Dependency"));
    }
}
