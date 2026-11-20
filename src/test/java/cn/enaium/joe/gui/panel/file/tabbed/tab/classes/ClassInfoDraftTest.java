package cn.enaium.joe.gui.panel.file.tabbed.tab.classes;

import cn.enaium.joe.util.classes.ClassNode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassInfoDraftTest {
    @Test
    void invalidNamesAreRejectedBeforeTheLiveClassChanges() {
        ClassNode live = ClassNode.of(classBytes("Example", Opcodes.V17));
        ClassInfoDraft.Values baseline = values(live);
        for (String name : List.of("", "/Example", "Example/", "a//B", "a.B", "A;B", "[LA;", "A\\B", "A\0B")) {
            assertFalse(ClassInfoDraft.validInternalName(name), name);
        }
        assertTrue(ClassInfoDraft.validInternalName("com/example/Outer$Inner"));

        ClassInfoDraft.InvalidValueException failure = assertThrows(ClassInfoDraft.InvalidValueException.class,
                () -> ClassInfoDraft.stage(live, withName(baseline, "a//B"), baseline));
        assertEquals("class.info.invalidName", failure.key());
        assertEquals("Example", live.getInternalName());
        assertEquals("Example", live.getClassNode().name);
    }

    @Test
    void changedFieldsAreStagedOnACopyAndUntouchedFieldsStayAsIs() {
        ClassNode live = ClassNode.of(classBytes("Example", Opcodes.V17));
        ClassInfoDraft.Values baseline = values(live);
        ClassInfoDraft.Values current = new ClassInfoDraft.Values("Example", baseline.sourceFile(), baseline.debugFile(),
                baseline.access(), baseline.version(), "Ljava/lang/Object;", baseline.superName(),
                "java/io/Serializable", baseline.outerClass(), baseline.outerMethod(), baseline.outerMethodDescription());

        ClassNode staged = ClassInfoDraft.stage(live, current, baseline);
        assertEquals("Ljava/lang/Object;", staged.getNodeInternal().signature);
        assertEquals(List.of("java/io/Serializable"), staged.getNodeInternal().interfaces);
        assertNull(live.getNodeInternal().signature);
        assertTrue(live.getNodeInternal().interfaces.isEmpty());
    }

    @Test
    void invalidAccessOrVersionLeavesTheLiveClassUntouched() {
        ClassNode live = ClassNode.of(classBytes("Example", Opcodes.V17));
        ClassInfoDraft.Values baseline = values(live);
        assertEquals("class.info.invalidVersion", assertThrows(ClassInfoDraft.InvalidValueException.class,
                () -> ClassInfoDraft.stage(live, withVersion(baseline, "44"), baseline)).key());
        assertEquals("class.info.invalidVersion", assertThrows(ClassInfoDraft.InvalidValueException.class,
                () -> ClassInfoDraft.stage(live, withVersion(baseline, "-1"), baseline)).key());
        assertEquals("class.info.invalidVersion", assertThrows(ClassInfoDraft.InvalidValueException.class,
                () -> ClassInfoDraft.stage(live, withVersion(baseline, "70000"), baseline)).key());
        assertEquals("class.info.invalidVersion", assertThrows(ClassInfoDraft.InvalidValueException.class,
                () -> ClassInfoDraft.stage(live, withVersion(baseline, "new"), baseline)).key());
        ClassInfoDraft.Values badAccess = new ClassInfoDraft.Values(baseline.name(), baseline.sourceFile(),
                baseline.debugFile(), "public", baseline.version(), baseline.signature(), baseline.superName(),
                baseline.interfaces(), baseline.outerClass(), baseline.outerMethod(), baseline.outerMethodDescription());
        assertEquals("class.info.invalidAccess", assertThrows(ClassInfoDraft.InvalidValueException.class,
                () -> ClassInfoDraft.stage(live, badAccess, baseline)).key());
        assertEquals(Opcodes.V17, live.getVersion());
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, live.getAccess());
    }

    @Test
    void editingOnlyTheMajorVersionPreservesPreviewBits() {
        assertEquals(Opcodes.V_PREVIEW | 65,
                ClassInfoDraft.parseVersion("65", Opcodes.V_PREVIEW | Opcodes.V17));
        assertEquals(65, ClassInfoDraft.parseVersion("65", Opcodes.V17));
    }

    private static ClassInfoDraft.Values values(ClassNode node) {
        var tree = node.getNodeInternal();
        return new ClassInfoDraft.Values(node.getInternalName(), text(tree.sourceFile), text(tree.sourceDebug),
                String.valueOf(tree.access), String.valueOf(tree.version), text(tree.signature),
                text(tree.superName), String.join(";", tree.interfaces), text(tree.outerClass),
                text(tree.outerMethod), text(tree.outerMethodDesc));
    }

    private static ClassInfoDraft.Values withName(ClassInfoDraft.Values baseline, String name) {
        return new ClassInfoDraft.Values(name, baseline.sourceFile(), baseline.debugFile(), baseline.access(),
                baseline.version(), baseline.signature(), baseline.superName(), baseline.interfaces(),
                baseline.outerClass(), baseline.outerMethod(), baseline.outerMethodDescription());
    }

    private static ClassInfoDraft.Values withVersion(ClassInfoDraft.Values baseline, String version) {
        return new ClassInfoDraft.Values(baseline.name(), baseline.sourceFile(), baseline.debugFile(),
                baseline.access(), version, baseline.signature(), baseline.superName(), baseline.interfaces(),
                baseline.outerClass(), baseline.outerMethod(), baseline.outerMethodDescription());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static byte[] classBytes(String name, int version) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
