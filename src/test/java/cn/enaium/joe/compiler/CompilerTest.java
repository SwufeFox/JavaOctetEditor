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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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

    @Test
    void archiveCompilationRejectsAccidentalEditorDependencies() {
        CompileError error = assertThrows(CompileError.class, () -> Compiler.compileSources(Map.of("Script", """
                public class Script {
                    public static String answer() { return org.objectweb.asm.Type.getType("I").getClassName(); }
                }
                """), Map.of()));
        assertTrue(error.getMessage().contains("org.objectweb.asm"), error.getMessage());
    }

    @Test
    void legacyArchiveEditCannotAcquireUndeclaredGsonDependency() {
        byte[] original = classBytes("Legacy", Opcodes.V1_8);
        byte[] before = original.clone();
        CompileError error = assertThrows(CompileError.class, () -> Compiler.compileSources(Map.of("Legacy", """
                public class Legacy {
                    public static String value() { return new com.google.gson.Gson().toJson("x"); }
                }
                """), Map.of("Legacy", original)));
        assertTrue(error.getMessage().contains("com.google.gson"), error.getMessage());
        assertArrayEquals(before, original);
    }

    @Test
    void applicationCompilationCombinesRuntimeAndExplicitDependencies() throws Exception {
        Map<String, byte[]> dependencies = Map.of("archive.Dependency", classBytes("archive.Dependency", Opcodes.V1_8));
        Map<String, byte[]> classes = Compiler.compileApplicationSources(Map.of("Script", """
                public class Script {
                    public static String answer() {
                        return org.objectweb.asm.Type.getType("I").getClassName() + archive.Dependency.value();
                    }
                    public static class Nested { }
                }
                """), dependencies);
        assertTrue(classes.containsKey("Script$Nested"));
        assertEquals(Runtime.version().feature() + 44, new ClassReader(classes.get("Script")).readUnsignedShort(6));
        Map<String, byte[]> loadable = new LinkedHashMap<>(dependencies);
        loadable.putAll(classes);
        assertEquals("int42", new MemoryClassLoader(getClass().getClassLoader(), loadable)
                .loadClass("Script").getMethod("answer").invoke(null));
    }

    @Test
    void convenienceAndInstanceCompilationKeepApplicationDependencies() throws Exception {
        String source = "public class Script { public static String answer() { return org.objectweb.asm.Type.getType(\"I\").getClassName(); } }";
        assertNotNull(Compiler.compileSingle("Script", source));
        Compiler compiler = new Compiler();
        compiler.addSource("Script", source);
        assertNull(compiler.compile());
        assertEquals("int", new MemoryClassLoader(getClass().getClassLoader(), compiler.getClasses())
                .loadClass("Script").getMethod("answer").invoke(null));
    }

    @Test
    void editingLegacyClassPreservesReleaseForEveryGeneratedClass() throws Exception {
        Map<String, byte[]> dependencies = Map.of("legacy.Target", classBytes("legacy.Target", Opcodes.V1_8));
        Map<String, byte[]> classes = Compiler.compileSources(Map.of("legacy.Target", """
                package legacy;
                public class Target {
                    public static int value() { return new Nested().answer(); }
                    public static class Nested { public int answer() { return 42; } }
                }
                """), dependencies);
        assertEquals(java.util.Set.of("legacy.Target", "legacy.Target$Nested"), classes.keySet());
        for (byte[] bytes : classes.values()) assertEquals(Opcodes.V1_8, new ClassReader(bytes).readUnsignedShort(6));
        assertEquals(42, new MemoryClassLoader(getClass().getClassLoader(), classes)
                .loadClass("legacy.Target").getMethod("value").invoke(null));
    }

    @Test
    void legacyReleaseRejectsNewerJdkApisWithoutMutatingInputs() {
        byte[] original = classBytes("legacy.Target", Opcodes.V1_8);
        Map<String, byte[]> dependencies = Map.of("legacy.Target", original);
        byte[] before = original.clone();
        Map<String, String> sources = Map.of("legacy.Target", """
                package legacy;
                public class Target { public boolean value() { return " ".isBlank(); } }
                """);
        CompileError error = assertThrows(CompileError.class, () -> Compiler.compileSources(sources, dependencies));
        assertTrue(error.getMessage().contains("isBlank"), error.getMessage());
        assertArrayEquals(before, original);
        assertSame(original, dependencies.get("legacy.Target"));
        assertEquals(1, sources.size());
    }

    @Test
    void newClassesUseExistingEditedReleaseOrUnambiguousArchiveRelease() throws Exception {
        Map<String, byte[]> dependencies = Map.of("legacy.Existing", classBytes("legacy.Existing", Opcodes.V1_8));
        String fresh = "package legacy; public class Fresh { public static int value() { return Existing.value(); } }";
        Map<String, byte[]> added = Compiler.compileSources(Map.of("legacy.Fresh", fresh), dependencies);
        assertEquals(Opcodes.V1_8, new ClassReader(added.get("legacy.Fresh")).readUnsignedShort(6));
        Map<String, byte[]> edited = Compiler.compileSources(Map.of(
                "legacy.Existing", "package legacy; public class Existing { public static int value() { return 42; } }",
                "legacy.Fresh", fresh), dependencies);
        for (byte[] bytes : edited.values()) assertEquals(Opcodes.V1_8, new ClassReader(bytes).readUnsignedShort(6));
    }

    @Test
    void emptyArchiveUsesRunningReleaseExplicitly() throws Exception {
        byte[] bytes = Compiler.compileSources(Map.of("Fresh", "public class Fresh { }"), Map.of()).get("Fresh");
        assertEquals(Runtime.version().feature() + 44, new ClassReader(bytes).readUnsignedShort(6));
    }

    @Test
    void mixedEditedTargetsAndAmbiguousNewClassTargetsAreRejected() {
        Map<String, byte[]> dependencies = Map.of(
                "Old", classBytes("Old", Opcodes.V1_8), "Newer", classBytes("Newer", Opcodes.V11));
        CompileError edited = assertThrows(CompileError.class, () -> Compiler.compileSources(Map.of(
                "Old", "public class Old { }", "Newer", "public class Newer { }"), dependencies));
        assertTrue(edited.getMessage().contains("mixed"), edited.getMessage());
        CompileError added = assertThrows(CompileError.class, () -> Compiler.compileSources(
                Map.of("Fresh", "public class Fresh { }"), dependencies));
        assertTrue(added.getMessage().contains("mixed"), added.getMessage());
    }

    @Test
    void existingClassTargetDoesNotDependOnUnrelatedArchiveVersions() throws Exception {
        Map<String, byte[]> classes = Compiler.compileSources(Map.of("Old", "public class Old { }"), Map.of(
                "Old", classBytes("Old", Opcodes.V1_8), "Newer", classBytes("Newer", Opcodes.V11)));
        assertEquals(Opcodes.V1_8, new ClassReader(classes.get("Old")).readUnsignedShort(6));
    }

    @Test
    void regeneratedNestedClassesCannotUpgradeAnOlderExistingTarget() {
        Map<String, byte[]> dependencies = Map.of(
                "Outer", classBytes("Outer", Opcodes.V11),
                "Outer$Nested", classBytes("Outer$Nested", Opcodes.V1_8));
        byte[] before = dependencies.get("Outer$Nested").clone();
        CompileError error = assertThrows(CompileError.class, () -> Compiler.compileSources(Map.of("Outer",
                "public class Outer { public static class Nested { } }"), dependencies));
        assertTrue(error.getMessage().contains("Outer$Nested"), error.getMessage());
        assertTrue(error.getMessage().contains("target"), error.getMessage());
        assertArrayEquals(before, dependencies.get("Outer$Nested"));
    }

    @Test
    void unsupportedAndPreviewTargetsFailWithActionableDiagnostics() {
        for (int version : new int[]{Opcodes.V1_1, Runtime.version().feature() + 45,
                Opcodes.V_PREVIEW | (Runtime.version().feature() + 44)}) {
            CompileError error = assertThrows(CompileError.class, () -> Compiler.compileSources(
                    Map.of("Target", "public class Target { }"), Map.of("Target", classBytes("Target", version))));
            assertTrue(error.getMessage().contains("target"), error.getMessage());
            assertTrue(error.getMessage().contains("Target"), error.getMessage());
        }
    }

    private static byte[] classBytes(String binaryName, int version) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(42);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
