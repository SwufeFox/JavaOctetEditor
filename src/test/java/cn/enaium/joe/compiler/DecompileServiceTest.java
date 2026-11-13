package cn.enaium.joe.compiler;

import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.service.DecompileService;
import cn.enaium.joe.service.decompiler.VineFlowerDecompiler;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.compiler.Compiler;
import cn.enaium.joe.util.compiler.virtual.MemoryClassLoader;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecompileServiceTest {
    @Test
    void everyBackendDecompilesAndRecompilesWithoutEditorInitialization() throws Exception {
        Map<String, byte[]> bytecode = Compiler.compileSources(Map.of(
                "sample.Dependency", """
                    package sample;
                    public class Dependency { protected int inherited() { return 40; } }
                    """,
                "sample.Target", """
                    package sample;
                    public class Target extends Dependency {
                        public int answer() { return inherited() + new Nested().value(); }
                        public static class Nested { public int value() { return 2; } }
                    }
                    """), Map.of());
        Jar archive = archive(bytecode);
        Map<String, byte[]> before = new LinkedHashMap<>();
        archive.getClasses().forEach(node -> before.put(node.getInternalName(), node.getClassBytes().clone()));
        for (DecompileService.Service backend : DecompileService.Service.values()) {
            String source = DecompileService.decompile(archive, "sample/Target", backend);
            assertTrue(source.contains("class Target"), backend + ": " + source);
            assertTrue(source.contains("class Nested"), backend + ": " + source);
            Map<String, byte[]> recompiled = Compiler.compileSources(Map.of("sample.Target", source), bytecode);
            assertTrue(recompiled.containsKey("sample.Target$Nested"), backend.toString());
            Map<String, byte[]> classes = new LinkedHashMap<>(bytecode);
            classes.putAll(recompiled);
            Class<?> target = new MemoryClassLoader(getClass().getClassLoader(), classes).loadClass("sample.Target");
            assertEquals(42, target.getMethod("answer").invoke(target.getConstructor().newInstance()), backend.toString());
            before.forEach((name, bytes) -> assertArrayEquals(bytes, archive.getClassNode(name).getClassBytes(), backend + ": " + name));
        }
    }

    @Test
    void repeatedCallsDoNotReusePreviousSourceOrArchive() throws Exception {
        Jar first = archive(Compiler.compileSources(Map.of("First", "public class First { public int first() { return 1; } }"), Map.of()));
        Jar second = archive(Compiler.compileSources(Map.of("Second", "public class Second { public int second() { return 2; } }"), Map.of()));
        for (DecompileService.Service backend : DecompileService.Service.values()) {
            assertTrue(DecompileService.decompile(first, "First", backend).contains("class First"));
            String source = DecompileService.decompile(second, "Second", backend);
            assertTrue(source.contains("class Second"), backend + ": " + source);
            assertFalse(source.contains("class First"), backend + ": " + source);
        }
    }

    @Test
    void missingClassesFailWithTheirNames() {
        for (DecompileService.Service backend : DecompileService.Service.values()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DecompileService.decompile(new Jar(), "missing/Type", backend));
            assertTrue(error.getMessage().contains("missing/Type"));
        }
    }

    @Test
    void vineflowerLibraryReturnsArchiveDependenciesAndResources() throws Exception {
        Map<String, byte[]> bytecode = Compiler.compileSources(Map.of("Library", "public class Library { }"), Map.of());
        Jar archive = archive(bytecode);
        byte[] resource = "archive resource".getBytes(StandardCharsets.UTF_8);
        archive.putResource("data/value.txt", resource);
        VineFlowerDecompiler.JarLibrary library = new VineFlowerDecompiler.JarLibrary(archive);
        assertArrayEquals(bytecode.get("Library"), library.getClassBytes("Library"));
        try (InputStream input = library.getInputStream("Library.class")) {
            assertNotNull(input);
            assertArrayEquals(bytecode.get("Library"), input.readAllBytes());
        }
        try (InputStream input = library.getInputStream("data/value.txt")) {
            assertNotNull(input);
            assertArrayEquals(resource, input.readAllBytes());
        }
        assertNull(library.getInputStream("missing/resource.txt"));
    }

    private static Jar archive(Map<String, byte[]> bytecode) {
        Jar archive = new Jar();
        bytecode.values().forEach(bytes -> archive.putClassNode(ClassNode.of(bytes)));
        return archive;
    }
}
