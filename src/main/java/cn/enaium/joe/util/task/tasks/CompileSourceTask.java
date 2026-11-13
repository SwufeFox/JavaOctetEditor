package cn.enaium.joe.util.task.tasks;

import cn.enaium.joe.jar.ArchiveCompilation;
import cn.enaium.joe.jar.ArchiveSnapshot;
import cn.enaium.joe.util.task.AbstractTask;
import cn.enaium.joe.util.task.Repeatable;

import java.util.Map;
import java.util.concurrent.CompletionException;

/** Stages source changes with the same content guards as headless archive editing. */
@Repeatable
public final class CompileSourceTask extends AbstractTask<ArchiveCompilation> {
    private final String className;
    private final String source;
    private final ArchiveSnapshot baseline;

    public CompileSourceTask(String className, String source, ArchiveSnapshot baseline) {
        super("Compile " + className);
        this.className = className;
        this.source = source;
        this.baseline = baseline;
    }

    @Override
    public ArchiveCompilation get() {
        checkCancelled();
        try {
            ArchiveCompilation compilation = ArchiveCompilation.compile(baseline, Map.of(className, source));
            setProgress(100);
            return compilation;
        } catch (cn.enaium.joe.util.compiler.CompileError failure) {
            throw new CompletionException(failure);
        }
    }
}
