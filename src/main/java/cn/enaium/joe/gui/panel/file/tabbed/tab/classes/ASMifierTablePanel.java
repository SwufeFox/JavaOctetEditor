/*
 * Copyright 2022 Enaium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.enaium.joe.gui.panel.file.tabbed.tab.classes;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.tasks.CompileVisitorTask;
import cn.enaium.joe.util.event.events.EditSaveSuccessEvent;
import cn.enaium.joe.gui.panel.CodeAreaPanel;
import cn.enaium.joe.util.*;
import cn.enaium.joe.util.classes.ASMClassLoader;
import org.fife.ui.autocomplete.*;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.objectweb.asm.*;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.tinylog.Logger;
import cn.enaium.joe.gui.editor.EditorDraft;
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.jar.ArchiveSnapshot;
import javax.swing.*;

import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author Enaium
 */
public class ASMifierTablePanel extends ClassNodeTabPanel implements EditorDraft {
    protected CodeAreaPanel codeAreaPanel;
    protected AutoCompletion autoCompletion;
    private CompletableFuture<ClassNode> compilation;
    private int generation;
    private final Jar owner;
    private final DraftSupport.TextState state;
    private final JLabel hint = new JLabel(LangUtil.i18n("source.exportHint"));
    private final JButton save = new JButton(LangUtil.i18n("draft.compileVisitor"));
    private CompletableFuture<Boolean> pendingSave;
    private byte[] expected;
    private boolean initialized;
    public ASMifierTablePanel(ClassNode classNode) {
        super(classNode);
        owner = JavaOctetEditor.getInstance().getJar();
        setLayout(new BorderLayout());
        codeAreaPanel = new CodeAreaPanel();
        state = new DraftSupport.TextState(codeAreaPanel.getTextArea(), () -> JavaOctetEditor.getInstance().draftsChanged());
        KeyStrokeUtil.register(codeAreaPanel.getTextArea(), JavaOctetEditor.getInstance().CONFIG
                .getByClass(ApplicationConfig.class).keymap.getValue().save.getValue(), () -> saveDraft());
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.add(hint, BorderLayout.CENTER);
        actions.add(save, BorderLayout.EAST);
        save.setEnabled(false);
        save.addActionListener(event -> saveDraft());
        add(actions, BorderLayout.NORTH);
        add(codeAreaPanel, BorderLayout.CENTER);
        codeAreaPanel.getTextArea().setCodeFoldingEnabled(true);
        codeAreaPanel.getTextArea().setEditable(false);
        codeAreaPanel.getTextArea().setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        AutoCompletion ac = autoCompletion = new AutoCompletion(new DefaultCompletionProvider());
        ac.setAutoActivationEnabled(true);
        ac.setAutoActivationDelay(100);
        ac.setAutoCompleteSingleChoices(false);
        ac.setParameterAssistanceEnabled(true);
        ac.install(codeAreaPanel.getTextArea());
        update();
    }

    public boolean hasDraft() { return state.isDirty(); }
    public long draftRevision() { return state.revision(); }
    public String draftName() { return getClassNode().getInternalName() + ".class / " + LangUtil.i18n("class.tab.visitorEdit"); }

    private boolean current() {
        return JavaOctetEditor.getInstance().isCurrentArchive(owner)
                && owner.getClassNode(getClassNode().getInternalName()) == getClassNode();
    }

    private boolean matchesDisplayedClass() {
        return current() && expected != null && Arrays.equals(expected, getClassNode().getClassBytes());
    }

    private void refreshHint() {
        hint.setText(LangUtil.i18n(!current() ? "draft.classRemoved"
                : expected != null && !matchesDisplayedClass() ? "draft.classStale" : "source.exportHint"));
    }

    public CompletableFuture<Boolean> saveDraft() {
        return DraftSupport.onEdt(() -> {
            if (pendingSave != null && !pendingSave.isDone()) return pendingSave;
            if (!initialized) return CompletableFuture.completedFuture(false);
            if (!matchesDisplayedClass()) {
                refreshHint();
                MessageUtil.error(LangUtil.i18n(current() ? "draft.classStale" : "draft.classRemoved"));
                return CompletableFuture.completedFuture(false);
            }
            if (!hasDraft()) return CompletableFuture.completedFuture(true);
            var app = JavaOctetEditor.getInstance();
            var baseline = ArchiveSnapshot.capture(owner);
            String content = codeAreaPanel.getTextArea().getText();
            String className = "ASMifier" + Integer.toHexString(getClassNode().getInternalName().hashCode())
                    + Integer.toHexString(content.hashCode());
            String source = "import org.objectweb.asm.*; public class " + className + " implements Opcodes {"
                    + "public static byte[] dump() throws Exception {" + content + "return classWriter.toByteArray();}}";
            Map<String, byte[]> classPath = new LinkedHashMap<>();
            for (ClassNode node : owner.getClasses()) classPath.put(node.getCanonicalName(), node.getClassBytes().clone());
            int request = ++generation;
            save.setEnabled(false);
            CompletableFuture<Boolean> result = pendingSave = new CompletableFuture<>();
            CompletableFuture<ClassNode> submitted = app.TASKS.submit(new CompileVisitorTask(className, source, classPath));
            compilation = submitted;
            result.whenComplete((saved, failure) -> {
                if (result.isCancelled()) submitted.cancel(true);
            });
            submitted.whenComplete((staged, failure) -> DraftSupport.onEdt(() -> {
                boolean committed = false;
                try {
                    if (failure != null || result.isCancelled() || submitted.isCancelled() || request != generation || !current() || !isDisplayable()) return;
                    if (!baseline.matches(owner)) {
                        MessageUtil.error(LangUtil.i18n("source.stale"));
                        return;
                    }
                    String oldName = getClassNode().getInternalName();
                    if (!oldName.equals(staged.getInternalName()) && owner.hasClass(staged.getInternalName())) {
                        MessageUtil.error(LangUtil.i18n("class.info.nameInUse", staged.getInternalName()));
                        return;
                    }
                    getClassNode().accept(staged);
                    expected = getClassNode().getClassBytes().clone();
                    state.applied(content);
                    committed = true;
                    app.markArchiveModified();
                    app.refreshJar();
                    EditSaveSuccessEvent.trigger(oldName);
                    app.notifyStatus(LangUtil.i18n("draft.applied"));
                } catch (RuntimeException error) {
                    MessageUtil.error(error);
                } finally {
                    save.setEnabled(initialized);
                    codeAreaPanel.getTextArea().setEditable(initialized);
                    refreshHint();
                    result.complete(committed);
                }
            }));
            return result;
        });
    }

    public static String getSimpleName(String name){
        int idx = name.lastIndexOf('/');
        if (idx != -1){
            return name.substring(idx + 1, name.length() - 1);
        } else return name.substring(name.lastIndexOf('.') + 1, name.length() - 1);
    }

    public void update() {
        refreshHint();
        if (hasDraft() || pendingSave != null && !pendingSave.isDone() || !current()) return;
        int request = ++generation;
        ClassNode snapshot = getClassNode().copy();
        byte[] displayedClass = snapshot.getClassBytes();
        long revision = state.revision();
        StringWriter stringWriter = new StringWriter();
        ASyncUtil.execute(() -> snapshot.trace(new TraceClassVisitor(null, new ASMifier(), new PrintWriter(stringWriter))), () -> {
            if (request != generation || !current()) return;
            if (hasDraft() || state.revision() != revision) { refreshHint(); return; }
            if (!Arrays.equals(displayedClass, getClassNode().getClassBytes())) { refreshHint(); return; }
            String trim = getMiddle(getMiddle(stringWriter.toString())).trim();
            expected = displayedClass;
            initialized = true;
            state.replaceClean(trim.substring(0, trim.lastIndexOf("\n")));
            codeAreaPanel.getTextArea().setEditable(true);
            save.setEnabled(true);
            refreshHint();
            DefaultCompletionProvider provider = (DefaultCompletionProvider) autoCompletion.getCompletionProvider();
            provider.clear();
            updateCompletionProvider(provider, codeAreaPanel.getTextArea().getText());
        });
    }

    @Override
    public void removeNotify() {
        generation++;
        if (compilation != null) compilation.cancel(true);
        super.removeNotify();
    }

    public String getMiddle(String s) {
        return s.substring(s.indexOf("{") + 1, s.lastIndexOf("}"));
    }

    public static void updateCompletionProvider(DefaultCompletionProvider defaultCompletionProvider, String baseString) {
        List<Completion> completions = new ArrayList<>(0);
        for (String str : new HashSet<>(extractQuotedStrings(baseString))) {
            completions.add(new DynamicBasicCompletion(defaultCompletionProvider, str));
        }

        for (String str : completionProvider()) {
            completions.add(new BasicCompletion(defaultCompletionProvider, str));
        }
        defaultCompletionProvider.addCompletions(completions);
        defaultCompletionProvider.setAutoActivationRules(true, ".abcdefghijklmnopqrstuvwxyz");
    }

    private static class DynamicBasicCompletion extends BasicCompletion{
        public DynamicBasicCompletion(CompletionProvider provider, String replacementText) {
            super(provider, replacementText);
        }
    }
    public static List<String> extractQuotedStrings(String text) {
        List<String> result = new ArrayList<>();
        int start = -1;

        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '"') {
                if (start == -1) {
                    start = i;
                } else {
                    String extracted = text.substring(start + 1, i);
                    result.add(extracted);
                    start = -1; // 重置标记
                }
            }
        }
        Logger.error("String Key Words for:" + text);
        Logger.error("String Key Words:" + Arrays.toString(result.toArray()));
        return result;
    }
    private static Set<String> $provider = null;
    private static Set<String> completionProvider() {
        if ($provider != null) return $provider;
        else {
            HashSet<String> keyWords = new HashSet<>();

            for (Field field : Opcodes.class.getFields()) {
                keyWords.add(field.getName());
            }
            for (Method method : MethodVisitor.class.getMethods()) {
                keyWords.add("methodVisitor." + method.getName());
            }
            for (Method method : FieldVisitor.class.getMethods()) {
                keyWords.add("fieldVisitor." + method.getName());
            }
            for (Method method : ClassWriter.class.getMethods()) {
                keyWords.add("classWriter." + method.getName());
            }
            for (Method method : AnnotationVisitor.class.getMethods()) {
                keyWords.add("annotationVisitor0." + method.getName());
            }
            for (Method method : RecordComponentVisitor.class.getMethods()) {
                keyWords.add("recordComponentVisitor." + method.getName());
            }

            keyWords.add("classWriter");
            keyWords.add("fieldVisitor");
            keyWords.add("recordComponentVisitor");
            keyWords.add("methodVisitor");
            keyWords.add("annotationVisitor0");

            return $provider = keyWords;
        }
    }
}
