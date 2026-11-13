package cn.enaium.joe.dialog;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.api.transformer.IClassTransformer;
import cn.enaium.joe.gui.panel.CodeAreaPanel;
import cn.enaium.joe.gui.panel.file.tabbed.tab.classes.ASMifierTablePanel;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.classes.ASMClassLoader;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.compiler.Compiler;
import cn.enaium.joe.util.event.events.EditSaveSuccessEvent;
import org.fife.rsta.ac.LanguageSupport;
import org.fife.rsta.ac.LanguageSupportFactory;
import org.fife.rsta.ac.java.JavaLanguageSupport;
import org.fife.ui.autocomplete.*;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TransformClassDialog extends Dialog {
    protected CodeAreaPanel codeAreaPanel = new CodeAreaPanel();
    protected javax.swing.JButton button;
    private java.util.concurrent.CompletableFuture<?> transformation;
    public TransformClassDialog() {
        super(LangUtil.i18n("menu.attach.transform"));
        setLayout(new BorderLayout());
        setSize(700, 400);
        add(codeAreaPanel, BorderLayout.CENTER);
        codeAreaPanel.getTextArea().setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        codeAreaPanel.getTextArea().setEditable(true);
        String hex = Long.toHexString(System.nanoTime());
        String className = "Transformer" + hex.substring(Math.max(0, hex.length() - 12));
        String stringBuilder =
                        "import org.objectweb.asm.*;\n" +
                        "import org.objectweb.asm.tree.*;\n" +
                        "public class " + className + " implements Opcodes, cn.enaium.joe.api.transformer.IClassTransformer{\n" +
                        "    public boolean transform(ClassNode classNode) {\n" +
                        "        return false;\n" +
                        "    }\n" +
                        "}";
        codeAreaPanel.getTextArea().setText(stringBuilder);
        add(button = new javax.swing.JButton(LangUtil.i18n("button.edit")), BorderLayout.SOUTH);
        AutoCompletion ac = new AutoCompletion(createCompletionProvider());

        ac.setAutoActivationEnabled(true);
        ac.setAutoActivationDelay(100);
        ac.setAutoCompleteSingleChoices(false);
        ac.setParameterAssistanceEnabled(true);
        ac.install(codeAreaPanel.getTextArea());

        button.addActionListener(event -> {
            var app = JavaOctetEditor.getInstance();
            var archive = app.getJar();
            if (archive == null) {
                MessageUtil.warning(LangUtil.i18n("workspace.open"));
                return;
            }
            String source = codeAreaPanel.getTextArea().getText();
            var snapshot = archive.copy();
            button.setEnabled(false);
            var task = app.TASKS.submit(new cn.enaium.joe.util.task.AbstractTask<cn.enaium.joe.jar.Jar>("Transform classes") {
                @Override
                public cn.enaium.joe.jar.Jar get() {
                    try {
                        java.util.Map<String, byte[]> classPath = new java.util.LinkedHashMap<>();
                        for (ClassNode node : snapshot.getClasses()) classPath.put(node.getCanonicalName(), node.getClassBytes());
                        var outputs = Compiler.compileSources(java.util.Map.of(className, source), classPath);
                        ASMClassLoader loader = new ASMClassLoader();
                        Class<?> type = loader.defineClass(className, outputs.get(className));
                        for (var output : outputs.entrySet()) {
                            if (!output.getKey().equals(className)) loader.defineClass(output.getKey(), output.getValue());
                        }
                        IClassTransformer transformer = (IClassTransformer) type.getConstructor().newInstance();
                        int completed = 0;
                        for (ClassNode node : snapshot.getClasses()) {
                            setProgress(completed++ * 100 / Math.max(1, snapshot.getClassSize()));
                            if (transformer.transform(node.getClassNode())) node.updateBytes();
                        }
                        snapshot.refresh();
                        setProgress(100);
                        return snapshot;
                    } catch (ReflectiveOperationException | cn.enaium.joe.util.compiler.CompileError failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                }
            });
            transformation = task;
            task.whenComplete((staged, failure) -> {
                button.setEnabled(true);
                if (failure != null || app.getJar() != archive || !isDisplayable()) return;
                app.setJar(staged);
                for (ClassNode node : staged.getClasses()) EditSaveSuccessEvent.trigger(node.getInternalName());
                MessageUtil.info(LangUtil.i18n("source.saved"));
            });
        });
    }

    @Override
    public void dispose() {
        if (transformation != null) transformation.cancel(true);
        super.dispose();
    }

    private static CompletionProvider $provider = null;
    private static CompletionProvider createCompletionProvider() {
        if ($provider != null) return $provider;
        else {
            DefaultCompletionProvider defaultCompletionProvider = new DefaultCompletionProvider();
            List<Completion> completions = new ArrayList<>(0);
            HashSet<String> keyWords = new HashSet<>();
            {
                extractKeyWordForClass(Opcodes.class, "Opcodes.", keyWords);
                extractKeyWordForClass(org.objectweb.asm.Type.class, "", keyWords);
            }
            {
                keyWords.add("AbstractInsnNode");
                keyWords.add("AnnotationNode");
                keyWords.add("ClassNode");
                keyWords.add("FieldInsnNode");
                keyWords.add("FieldNode");
                keyWords.add("FrameNode");
                keyWords.add("IincInsnNode");
                keyWords.add("InnerClassNode");
                keyWords.add("InsnList");
                keyWords.add("InsnNode");
                keyWords.add("IntInsnNode");
                keyWords.add("InvokeDynamicInsnNode");
                keyWords.add("JumpInsnNode");
                keyWords.add("LabelNode");
                keyWords.add("LdcInsnNode");
                keyWords.add("LineNumberNode");
                keyWords.add("LocalVariableAnnotationNode");
                keyWords.add("LocalVariableNode");
                keyWords.add("LookupSwitchInsnNode");
                keyWords.add("MethodInsnNode");
                keyWords.add("MethodNode");
                keyWords.add("ModuleExportNode");
                keyWords.add("ModuleNode");
                keyWords.add("ModuleOpenNode");
                keyWords.add("ModuleProvideNode");
                keyWords.add("ModuleRequireNode");
                keyWords.add("MultiANewArrayInsnNode");
                keyWords.add("ParameterNode");
                keyWords.add("RecordComponentNode");
                keyWords.add("TableSwitchInsnNode");
                keyWords.add("TryCatchBlockNode");
                keyWords.add("TypeAnnotationNode");
                keyWords.add("TypeInsnNode");
                keyWords.add("UnsupportedClassVersionException");
                keyWords.add("VarInsnNode");
                extractKeyWordForClass(org.objectweb.asm.tree.ClassNode.class, "", keyWords);
                extractKeyWordForClass(MethodNode.class, "", keyWords);
                extractKeyWordForClass(FieldNode.class, "", keyWords);
                extractKeyWordForClass(List.class, "", keyWords);
            }
            {
                keyWords.add("classNode");
                keyWords.add("var");
                keyWords.add("for");
                keyWords.add("if");
                keyWords.add("while");
                keyWords.add("true");
                keyWords.add("false");
                keyWords.add("return");
                keyWords.add("break");
            }
            for (String str : keyWords) {
                completions.add(new BasicCompletion(defaultCompletionProvider, str));
            }
            defaultCompletionProvider.addCompletions(completions);
            defaultCompletionProvider.setAutoActivationRules(true, ".abcdefghijklmnopqrstuvwxyz");
            return $provider = defaultCompletionProvider;
        }
    }

    private static void extractKeyWordForClass(Class<?> cls, String prefix, Set<String> strings){
        for (var method : cls.getDeclaredFields()) {
            strings.add(prefix + method.getName());
        }
        for (var method : cls.getDeclaredMethods()) {
            strings.add(prefix + method.getName());
        }
    }
}
