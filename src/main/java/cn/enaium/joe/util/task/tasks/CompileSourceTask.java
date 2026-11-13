package cn.enaium.joe.util.task.tasks;

import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.compiler.Compiler;
import cn.enaium.joe.util.task.AbstractTask;
import cn.enaium.joe.util.task.Repeatable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compiles and validates every output before the UI commits any archive changes. */
@Repeatable
public final class CompileSourceTask extends AbstractTask<Map<String, ClassNode>> {
    private final String className;
    private final String source;
    private final Map<String, byte[]> classPath;

    public CompileSourceTask(String className, String source, Map<String, byte[]> classPath) {
        super("Compile " + className);
        this.className = className;
        this.source = source;
        this.classPath = Map.copyOf(classPath);
    }

    @Override
    public Map<String, ClassNode> get() {
        Map<String, ClassNode> staged = new LinkedHashMap<>();
        Map<String, byte[]> output;
        try {
            output = Compiler.compileSources(Map.of(className, source), classPath);
        } catch (cn.enaium.joe.util.compiler.CompileError failure) {
            throw new java.util.concurrent.CompletionException(failure);
        }
        for (var entry : output.entrySet()) {
            setProgress(staged.size() * 100 / Math.max(1, output.size()));
            ClassNode node = ClassNode.of(entry.getValue());
            if (!node.getCanonicalName().equals(entry.getKey())) {
                throw new IllegalStateException("Compiler output name mismatch: " + entry.getKey());
            }
            staged.put(node.getInternalName(), node);
        }
        if (!staged.containsKey(className.replace('.', '/'))) {
            throw new IllegalArgumentException("Compiled source must retain class name " + className);
        }
        setProgress(100);
        return staged;
    }
}
