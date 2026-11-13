package cn.enaium.joe.util.task.tasks;

import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.AbstractTask;
import cn.enaium.joe.util.transformer.ITransformer;

public class TransformerTask extends AbstractTask<cn.enaium.joe.jar.Jar> {
    private final ITransformer<ClassNode> transformer;
    private final cn.enaium.joe.jar.Jar staged;

    public TransformerTask(cn.enaium.joe.jar.Jar archive, ITransformer<ClassNode> classNodeITransformer) {
        super("Transforming");
        this.transformer = classNodeITransformer;
        this.staged = archive.copy();
    }

    @Override
    public cn.enaium.joe.jar.Jar get() {
        int completed = 0;
        for (ClassNode classNode : staged.getClasses()) {
            setProgress(completed++ * 100 / Math.max(1, staged.getClassSize()));
            transformer.transform(classNode.getCanonicalName(), classNode);
            classNode.updateBytes();
        }
        staged.refresh();
        setProgress(100);
        return staged;
    }


}
