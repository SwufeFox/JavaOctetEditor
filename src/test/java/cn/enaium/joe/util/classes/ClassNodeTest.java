package cn.enaium.joe.util.classes;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ClassNodeTest {
    @Test
    void packageNamesIncludeTheWholePackageAndSupportDefaultPackage() {
        ClassNode packaged = ClassNode.of(classBytes("one/two/Example", 1));
        assertEquals("one/two", packaged.getInternalPackageName());
        assertEquals("one.two", packaged.getCanonicalPackageName());
        assertEquals("Example", packaged.getSimpleName());
        assertEquals("one.two.Example", packaged.getCanonicalName());

        ClassNode unqualified = ClassNode.of(classBytes("Example", 1));
        assertEquals("", unqualified.getInternalPackageName());
        assertEquals("", unqualified.getCanonicalPackageName());
        assertEquals("Example", unqualified.getSimpleName());
    }

    @Test
    void readOnlyAccessDoesNotSerializeTheClassAgain() {
        ClassNode node = ClassNode.of(classBytes("Example", 1));
        byte[] bytes = node.getClassBytes();
        node.getInternalName();
        node.getCanonicalName();
        node.getMethods();
        node.getFields();
        node.getParents();
        node.analyzeVisitor(new ClassWriter(0));
        assertSame(bytes, node.getClassBytes());
    }

    @Test
    void retainedMutableTreeEditsRemainVisibleAfterEarlierSerialization() throws Exception {
        ClassNode node = ClassNode.of(classBytes("Example", 1));
        var tree = node.getClassNode();
        replaceValue(tree, 2);
        assertEquals(2, value(node.getClassBytes()));
        replaceValue(tree, 3);
        assertEquals(3, value(node.getClassBytes()));
        tree.name = "Renamed";
        assertEquals("Renamed", node.getInternalName());
        assertEquals("Renamed", new ClassReader(node.getClassBytes()).getClassName());
    }

    @Test
    void explicitDirtyMarkRebuildsOnceAndThenCachesBytes() throws Exception {
        ClassNode node = ClassNode.of(classBytes("Example", 1));
        replaceValue(node.getNodeInternal(), 4);
        node.mkdir();
        byte[] edited = node.getClassBytes();
        assertEquals(4, value(edited));
        assertSame(edited, node.getClassBytes());
    }

    @Test
    void inputBytesAndInputTreeAreOwnedByTheWrapper() {
        byte[] input = classBytes("Example", 1);
        ClassNode node = ClassNode.of(input);
        Arrays.fill(input, (byte) 0);
        assertEquals("Example", new ClassReader(node.getClassBytes()).getClassName());

        var externalTree = ClassNode.of(classBytes("External", 1)).getClassNode();
        ClassNode copiedTree = ClassNode.of(externalTree);
        externalTree.name = "ChangedElsewhere";
        assertEquals("External", copiedTree.getInternalName());
        assertEquals("External", new ClassReader(copiedTree.getClassBytes()).getClassName());
    }

    @Test
    void copyAndAcceptDoNotShareMutableTreesOrByteArrays() throws Exception {
        ClassNode original = ClassNode.of(classBytes("Example", 1));
        replaceValue(original.getClassNode(), 5);
        ClassNode copy = original.copy();
        assertEquals(5, value(copy.getClassBytes()));
        assertNotSame(original.getClassBytes(), copy.getClassBytes());
        replaceValue(copy.getClassNode(), 6);
        assertEquals(5, value(original.getClassBytes()));
        assertEquals(6, value(copy.getClassBytes()));

        ClassNode replacement = ClassNode.of(classBytes("Replacement", 7));
        var oldTree = original.getClassNode();
        original.accept(replacement);
        oldTree.name = "ObsoleteReference";
        replaceValue(replacement.getClassNode(), 8);
        replacement.getClassNode().name = "Other";
        assertEquals("Replacement", original.getInternalName());
        assertEquals(7, value(original.getClassBytes()));
        assertNotSame(original.getNodeInternal(), replacement.getNodeInternal());
        assertNotSame(original.getClassBytes(), replacement.getClassBytes());
    }

    private static void replaceValue(org.objectweb.asm.tree.ClassNode tree, int value) {
        var method = tree.methods.getFirst();
        method.instructions.clear();
        method.instructions.add(new LdcInsnNode(value));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
    }

    private static int value(byte[] bytes) throws Exception {
        String name = new ClassReader(bytes).getClassName().replace('/', '.');
        return (int) new ASMClassLoader().defineClass(name, bytes).getMethod("value").invoke(null);
    }

    private static byte[] classBytes(String name, int value) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
