package cn.enaium.joe.util.task.tasks;

import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.AbstractTask;
import cn.enaium.joe.util.transformer.ITransformer;

public class TransformerTask extends AbstractTask<cn.enaium.joe.util.task.ArchiveChange> {
    private final ITransformer<ClassNode> transformer;
    private final cn.enaium.joe.jar.Jar staged;
    private final cn.enaium.joe.jar.ArchiveSnapshot baseline;

    public TransformerTask(cn.enaium.joe.jar.Jar archive, ITransformer<ClassNode> classNodeITransformer) {
        super("Transforming");
        this.transformer = classNodeITransformer;
        this.baseline = cn.enaium.joe.jar.ArchiveSnapshot.capture(archive);
        this.staged = baseline.toJar();
    }

    @Override
    public cn.enaium.joe.util.task.ArchiveChange get() {
        int completed = 0;
        for (ClassNode classNode : staged.getClasses()) {
            setProgress(completed++ * 100 / Math.max(1, staged.getClassSize()));
            transformer.transform(classNode.getCanonicalName(), classNode);
            classNode.updateBytes();
        }
        staged.refresh();
        setProgress(100);
        return new cn.enaium.joe.util.task.ArchiveChange(baseline, staged);
    }


}
