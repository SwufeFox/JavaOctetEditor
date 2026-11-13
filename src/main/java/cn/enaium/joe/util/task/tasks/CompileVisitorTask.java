package cn.enaium.joe.util.task.tasks;

import cn.enaium.joe.util.classes.ASMClassLoader;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.compiler.Compiler;
import cn.enaium.joe.util.task.AbstractTask;
import cn.enaium.joe.util.task.Repeatable;

import java.util.Map;
import java.util.concurrent.CompletionException;

/** Runs a visitor editor's generated dump method without mutating the archive. */
@Repeatable
public final class CompileVisitorTask extends AbstractTask<ClassNode> {
    private final String name;
    private final String source;
    private final Map<String, byte[]> classPath;

    public CompileVisitorTask(String name, String source, Map<String, byte[]> classPath) {
        super("Compile visitor");
        this.name = name;
        this.source = source;
        this.classPath = Map.copyOf(classPath);
    }

    @Override
    public ClassNode get() {
        try {
            var outputs = Compiler.compileApplicationSources(Map.of(name, source), classPath);
            byte[] generated = outputs.get(name);
            if (generated == null) throw new IllegalStateException("Visitor compiler produced no " + name);
            ASMClassLoader loader = new ASMClassLoader();
            Class<?> type = loader.defineClass(name, generated);
            for (var output : outputs.entrySet()) {
                if (!output.getKey().equals(name)) loader.defineClass(output.getKey(), output.getValue());
            }
            byte[] bytes = (byte[]) type.getMethod("dump").invoke(null);
            setProgress(100);
            return ClassNode.of(bytes);
        } catch (ReflectiveOperationException | cn.enaium.joe.util.compiler.CompileError failure) {
            throw new CompletionException(failure);
        }
    }
}
