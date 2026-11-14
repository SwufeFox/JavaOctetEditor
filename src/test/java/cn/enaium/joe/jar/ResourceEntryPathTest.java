package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class ResourceEntryPathTest {
    @Test
    void baseClassPathsRemainDistinctFromOpaqueVersionedClasses() {
        Jar archive = new Jar();
        assertEquals("META-INF/versions/21/pkg/Example.class",
                ResourceEntryPath.validate(archive, "META-INF/versions/21/pkg/Example.class"));
        for (String path : new String[]{"pkg/Example.class", "meta-inf/versions/21/Example.class",
                "META-INF/versionsX/21/Example.class"}) {
            ResourceEntryPath.ValidationException failure = assertThrows(ResourceEntryPath.ValidationException.class,
                    () -> ResourceEntryPath.validate(archive, path));
            assertEquals(ResourceEntryPath.Reason.BASE_CLASS, failure.getReason());
        }
    }

    @Test
    void relativeFilePathsRejectTraversalAndMalformedSegmentsWithoutNormalizing() {
        Jar archive = new Jar();
        for (String path : new String[]{null, "", " ", "../escape", "a//b", "a/./b", "/absolute",
                "C:/absolute", "a\\b", "a\0b", "a/"}) {
            ResourceEntryPath.ValidationException failure = assertThrows(ResourceEntryPath.ValidationException.class,
                    () -> ResourceEntryPath.validate(archive, path));
            assertEquals(ResourceEntryPath.Reason.INVALID_PATH, failure.getReason());
        }
        assertEquals("资源/config.json", ResourceEntryPath.validate(archive, "资源/config.json"));
    }

    @Test
    void resourceAndClassFileDirectoryCollisionsAreRejectedButExactResourceReplacementIsAllowed() {
        Jar archive = new Jar();
        archive.putResource("assets/icon.png", new byte[]{1});
        archive.putResource("manifest", new byte[]{2});
        archive.putResource("empty/", new byte[0]);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "pkg/Example", null, "java/lang/Object", null);
        writer.visitEnd();
        archive.putClassNode(ClassNode.of(writer.toByteArray()));
        assertEquals("empty/config.txt", ResourceEntryPath.validate(archive, "empty/config.txt"));
        assertEquals("assets/icon.png", ResourceEntryPath.validate(archive, "assets/icon.png"));
        for (String path : new String[]{"assets", "manifest/child", "empty", "pkg", "pkg/Example.class/child"}) {
            ResourceEntryPath.ValidationException failure = assertThrows(ResourceEntryPath.ValidationException.class,
                    () -> ResourceEntryPath.validate(archive, path));
            assertEquals(ResourceEntryPath.Reason.COLLISION, failure.getReason());
        }
        assertArrayEquals(new byte[]{1}, archive.getResource("assets/icon.png"));
        assertEquals(3, archive.getResourceSize());
        assertEquals(1, archive.getClassSize());
    }
}
