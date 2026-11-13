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
import cn.enaium.joe.util.KeyStrokeUtil;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.tasks.CompileSourceTask;
import cn.enaium.joe.util.event.events.EditSaveSuccessEvent;
import cn.enaium.joe.gui.panel.CodeAreaPanel;
import cn.enaium.joe.util.task.tasks.DecompileTask;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @author Enaium
 */
public class DecompileTabPanel extends ClassNodeTabPanel {
    protected CodeAreaPanel codeAreaPanel;
    private final JButton save = new JButton(LangUtil.i18n("source.compile"));
    private CompletableFuture<?> decompilation;
    private CompletableFuture<?> compilation;
    private int generation;

    public DecompileTabPanel(ClassNode classNode) {
        super(classNode);
        setLayout(new BorderLayout());
        codeAreaPanel = new CodeAreaPanel();
        KeyStrokeUtil.register(codeAreaPanel.getTextArea(), JavaOctetEditor.getInstance().CONFIG
                .getByClass(ApplicationConfig.class).keymap.getValue().save.getValue(), this::saveSource);
        save.setEnabled(false);
        save.addActionListener(event -> saveSource());
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.add(new JLabel(LangUtil.i18n("source.exportHint")), BorderLayout.CENTER);
        actions.add(save, BorderLayout.EAST);
        add(actions, BorderLayout.NORTH);
        codeAreaPanel.getTextArea().setEditable(false);
        codeAreaPanel.getTextArea().setSyntaxEditingStyle("text/java");
        add(codeAreaPanel, BorderLayout.CENTER);
        update();
    }

    private void saveSource() {
        if (!isShowing() || !codeAreaPanel.getTextArea().isEditable()
                || (compilation != null && !compilation.isDone())) return;
        var app = JavaOctetEditor.getInstance();
        var archive = app.getJar();
        ClassNode original = getClassNode();
        if (archive == null || archive.getClassNode(original.getInternalName()) != original) return;
        Map<String, byte[]> classPath = new LinkedHashMap<>();
        for (ClassNode node : archive.getClasses()) classPath.put(node.getCanonicalName(), node.getClassBytes());
        save.setEnabled(false);
        codeAreaPanel.getTextArea().setEditable(false);
        CompletableFuture<Map<String, ClassNode>> submitted = app.TASKS.submit(new CompileSourceTask(original.getCanonicalName(),
                codeAreaPanel.getTextArea().getText(), classPath));
        compilation = submitted;
        submitted.whenComplete((staged, failure) -> {
            save.setEnabled(true);
            codeAreaPanel.getTextArea().setEditable(true);
            if (failure != null || app.getJar() != archive || !isDisplayable()) return;
            for (var entry : staged.entrySet()) {
                ClassNode existing = archive.getClassNode(entry.getKey());
                if (existing == null) archive.putClassNode(entry.getValue());
                else existing.accept(entry.getValue());
            }
            app.refreshJar();
            for (String internalName : staged.keySet()) EditSaveSuccessEvent.trigger(internalName);
            MessageUtil.info(LangUtil.i18n("source.saved"));
        });
    }

    public void update() {
        int request = ++generation;
        if (decompilation != null && !decompilation.isDone()) decompilation.cancel(true);
        save.setEnabled(false);
        codeAreaPanel.getTextArea().setEditable(false);
        codeAreaPanel.getTextArea().setText(LangUtil.i18n("source.loading"));
        CompletableFuture<String> submitted = JavaOctetEditor.getInstance().TASKS.submit(new DecompileTask(getClassNode()));
        decompilation = submitted;
        submitted.whenComplete((source, failure) -> {
                    if (request != generation) return;
                    String message = failure instanceof java.util.concurrent.CancellationException ? "source.cancelled" : "source.failed";
                    codeAreaPanel.getTextArea().setText(failure == null ? source : LangUtil.i18n(message));
                    codeAreaPanel.getTextArea().setCaretPosition(0);
                    codeAreaPanel.getTextArea().setEditable(failure == null);
                    save.setEnabled(failure == null);
                });
    }

    @Override
    public void removeNotify() {
        generation++;
        if (decompilation != null) decompilation.cancel(true);
        if (compilation != null) compilation.cancel(true);
        super.removeNotify();
    }
}
