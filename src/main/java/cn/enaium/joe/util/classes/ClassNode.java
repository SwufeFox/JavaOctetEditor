package cn.enaium.joe.util.classes;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public interface ClassNode {
    default String getInternalName(){
        return this.getNodeInternal().name;
    }
    default String getCanonicalName(){
        return this.getNodeInternal().name.replace('/', '.');
    }
    default ClassNode copy() {
        return ClassNode.of(this.getClassBytes());
    }
    /** Returns cached bytecode; callers must treat the returned array as read-only. */
    byte[] getClassBytes();

    /** Returns the live mutable tree and enables serialization of subsequent edits. */
    org.objectweb.asm.tree.ClassNode getClassNode();
    void accept(ClassNode newNode);
    void updateBytes();
    default String getSimpleName(){
        return this.getInternalName().substring(this.getInternalName().lastIndexOf('/') + 1);
    }

    default String getCanonicalPackageName(){
        return this.getInternalPackageName().replace('/', '.');
    }

    default String getInternalPackageName(){
        String internalName = this.getInternalName();
        int separator = internalName.lastIndexOf('/');
        return separator < 0 ? "" : internalName.substring(0, separator);
    }

    default String[] getInnerClassesInternalName(){
        return this.getNodeInternal().innerClasses.stream().map((v) -> v.name).toArray(String[]::new);
    }

    default String[] getInnerClassesCanonicalName(){
        return this.getNodeInternal().innerClasses.stream().map((v) -> v.name.replace('/', '.')).toArray(String[]::new);
    }

    default String getSuperClass(){
        return this.getNodeInternal().superName;
    }

    default String[] getInterfaces(){
        return this.getNodeInternal().interfaces.toArray(String[]::new);
    }

    default int getAccess(){
        return this.getNodeInternal().access;
    }

    default int getVersion(){
        return this.getNodeInternal().version;
    }

    /** Read-only member view; use getClassNode() before editing members. */
    default MethodNode[] getMethods(){
        return this.getNodeInternal().methods.toArray(MethodNode[]::new);
    }

    /** Read-only member view; use getClassNode() before editing members. */
    default FieldNode[] getFields(){
        return this.getNodeInternal().fields.toArray(FieldNode[]::new);
    }

    default void trace(TraceClassVisitor traceClassVisitor){
        this.getNodeInternal().accept(traceClassVisitor);
    }

    default void analyzeVisitor(ClassVisitor classVisitor){
        this.getNodeInternal().accept(classVisitor);
    }

    default void editVisitor(ClassVisitor classVisitor){
        this.getNodeInternal().accept(classVisitor);
        this.updateBytes();
    }

    /** Marks edits made through getNodeInternal() for the next serialization. */
    void mkdir();

    /** Read-only tree view. Mutating callers must use getClassNode() or call mkdir(). */
    org.objectweb.asm.tree.ClassNode getNodeInternal();

    default Set<String> getParents(){
        Set<String> parent = new HashSet<>();
        if (this.getSuperClass() != null && !"java/lang/Object".equals(this.getSuperClass())) {
            parent.add(this.getSuperClass());
        }
        parent.addAll(List.of(this.getInterfaces()));
        return parent;
    }

    default String getOuterClassInternalName(){
        return this.getNodeInternal().outerClass;
    }

    static ClassNode of(final byte[] classIn){
        ClassReader classReader = new ClassReader(classIn);
        final org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
        classReader.accept(classNode, 0);
        return new Impl(classIn.clone(), classNode);
    }

    static ClassNode of(final org.objectweb.asm.tree.ClassNode classNode){
        ClassWriter classWriter = new ClassWriter(0);
        classNode.accept(classWriter);
        byte[] bytes = classWriter.toByteArray();
        org.objectweb.asm.tree.ClassNode ownedNode = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(ownedNode, 0);
        return new Impl(bytes, ownedNode);
    }

    static ClassNode of(final String internalName) {
        org.objectweb.asm.tree.ClassNode classNode = new org.objectweb.asm.tree.ClassNode();
        try {
            new ClassReader(internalName).accept(classNode, 0);
        } catch (IOException e) {
            return null;
        }
        return of(classNode);
    }

    class Impl implements ClassNode {
        private boolean dirty;
        private boolean mutableTreeExposed;
        private byte[] clazz;
        private org.objectweb.asm.tree.ClassNode node;

        private Impl(byte[] clazz, org.objectweb.asm.tree.ClassNode node) {
            this.clazz = clazz;
            this.node = node;
        }

        @Override
        public byte[] getClassBytes() {
            if (dirty || mutableTreeExposed) updateBytes();
            return clazz;
        }

        @Override
        public org.objectweb.asm.tree.ClassNode getClassNode() {
            // A retained mutable reference can change again after a bytecode read.
            mutableTreeExposed = true;
            return node;
        }

        @Override
        public void updateBytes() {
            ClassWriter classWriter = new ClassWriter(0);
            node.accept(classWriter);
            clazz = classWriter.toByteArray();
            dirty = false;
        }

        @Override
        public void accept(ClassNode newNode) {
            Impl replacement = (Impl) ClassNode.of(newNode.getClassBytes());
            clazz = replacement.clazz;
            node = replacement.node;
            dirty = false;
            mutableTreeExposed = false;
        }

        @Override
        public ClassNode clone() {
            return copy();
        }

        @Override
        public org.objectweb.asm.tree.ClassNode getNodeInternal() {
            return node;
        }

        @Override
        public void mkdir() {
            dirty = true;
        }
    }
}
