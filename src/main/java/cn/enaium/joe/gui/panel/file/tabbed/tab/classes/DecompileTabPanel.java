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
import cn.enaium.joe.jar.ArchiveSnapshot;
import cn.enaium.joe.jar.ArchiveCompilation;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.util.KeyStrokeUtil;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.tasks.CompileSourceTask;
import cn.enaium.joe.util.event.events.EditSaveSuccessEvent;
import cn.enaium.joe.gui.panel.CodeAreaPanel;
import cn.enaium.joe.util.task.tasks.DecompileTask;
import cn.enaium.joe.gui.editor.EditorDraft;
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.jar.Jar;
import java.util.Arrays;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

/**
 * @author Enaium
 */
public class DecompileTabPanel extends ClassNodeTabPanel implements EditorDraft {
    protected CodeAreaPanel codeAreaPanel;
    private final JButton save = new JButton(LangUtil.i18n("source.compile"));
    private final JLabel hint = new JLabel(LangUtil.i18n("source.exportHint"));
    private final Jar owner;
    private final DraftSupport.TextState state;
    private byte[] expected;
    private CompletableFuture<?> decompilation;
    private CompletableFuture<?> compilation;
    private CompletableFuture<Boolean> pendingSave;
    private int generation;
    private boolean initialized;

    public DecompileTabPanel(ClassNode classNode) {
        super(classNode);
        owner = JavaOctetEditor.getInstance().getJar();
        setLayout(new BorderLayout());
        codeAreaPanel = new CodeAreaPanel();
        state = new DraftSupport.TextState(codeAreaPanel.getTextArea(), () -> JavaOctetEditor.getInstance().draftsChanged());
        KeyStrokeUtil.register(codeAreaPanel.getTextArea(), JavaOctetEditor.getInstance().CONFIG
                .getByClass(ApplicationConfig.class).keymap.getValue().save.getValue(), () -> saveDraft());
        save.setEnabled(false);
        save.addActionListener(event -> saveDraft());
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.add(hint, BorderLayout.CENTER);
        actions.add(save, BorderLayout.EAST);
        add(actions, BorderLayout.NORTH);
        codeAreaPanel.getTextArea().setEditable(false);
        codeAreaPanel.getTextArea().setSyntaxEditingStyle("text/java");
        add(codeAreaPanel, BorderLayout.CENTER);
        update();
    }

    public boolean hasDraft() { return state.isDirty(); }
    public long draftRevision() { return state.revision(); }
    public String draftName() { return getClassNode().getInternalName() + ".class / " + LangUtil.i18n("class.tab.decompileView"); }

    private boolean current() {
        var app = JavaOctetEditor.getInstance();
        return app.isCurrentArchive(owner) && owner.getClassNode(getClassNode().getInternalName()) == getClassNode();
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
            ClassNode original = getClassNode();
            ArchiveSnapshot baseline = ArchiveSnapshot.capture(owner);
            String source = codeAreaPanel.getTextArea().getText();
            int request = ++generation;
            if (decompilation != null) decompilation.cancel(true);
            save.setEnabled(false);
            CompletableFuture<Boolean> result = pendingSave = new CompletableFuture<>();
            CompletableFuture<ArchiveCompilation> submitted = app.TASKS.submit(
                    new CompileSourceTask(original.getCanonicalName(), source, baseline));
            compilation = submitted;
            result.whenComplete((saved, failure) -> {
                if (result.isCancelled()) submitted.cancel(true);
            });
            submitted.whenComplete((staged, failure) -> DraftSupport.onEdt(() -> {
                boolean committed = false;
                try {
                    if (failure != null || result.isCancelled() || submitted.isCancelled() || request != generation || !current() || !isDisplayable()) return;
                    ArchiveCompilation.Change change = staged.applyTo(owner, submitted::isCancelled);
                    expected = original.getClassBytes().clone();
                    state.applied(source);
                    committed = true;
                    app.markArchiveModified();
                    app.refreshJar();
                    for (String name : change.generatedClasses()) EditSaveSuccessEvent.trigger(name);
                    app.notifyStatus(LangUtil.i18n("draft.applied"));
                } catch (java.util.ConcurrentModificationException stale) {
                    MessageUtil.error(LangUtil.i18n("source.stale"));
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

    public void update() {
        refreshHint();
        if (hasDraft() || pendingSave != null && !pendingSave.isDone() || !current()) return;
        int request = ++generation;
        var app = JavaOctetEditor.getInstance();
        ArchiveSnapshot baseline = ArchiveSnapshot.capture(owner);
        byte[] displayedClass = getClassNode().getClassBytes().clone();
        if (decompilation != null && !decompilation.isDone()) decompilation.cancel(true);
        if (!initialized) hint.setText(LangUtil.i18n("source.loading"));
        long revision = state.revision();
        CompletableFuture<String> submitted = app.TASKS.submit(new DecompileTask(baseline, getClassNode().getInternalName()));
        decompilation = submitted;
        submitted.whenComplete((source, failure) -> DraftSupport.onEdt(() -> {
            if (request != generation || !current()) return;
            if (hasDraft() || state.revision() != revision) { refreshHint(); return; }
            if (failure != null) {
                hint.setText(LangUtil.i18n(submitted.isCancelled() ? "source.cancelled" : "source.failed"));
                return;
            }
            if (!baseline.matches(owner)) { refreshHint(); return; }
            expected = displayedClass;
            initialized = true;
            state.replaceClean(source);
            codeAreaPanel.getTextArea().setEditable(true);
            save.setEnabled(true);
            refreshHint();
        }));
    }

    @Override
    public void removeNotify() {
        generation++;
        if (decompilation != null) decompilation.cancel(true);
        if (compilation != null) compilation.cancel(true);
        super.removeNotify();
    }
}
