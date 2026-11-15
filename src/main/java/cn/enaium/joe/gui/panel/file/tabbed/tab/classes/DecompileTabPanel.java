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
import cn.enaium.joe.service.DecompileService;
import cn.enaium.joe.util.compiler.CompileError;
import org.tinylog.Logger;
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
    private final JButton retry = new JButton(LangUtil.i18n("source.retry"));
    private final JButton cancelDecompilation = new JButton(LangUtil.i18n("source.cancel"));
    private final JButton details = new JButton(LangUtil.i18n("source.details"));
    private final JComboBox<DecompileService.Service> backendChoice = new JComboBox<>(DecompileService.Service.values());
    private final Jar owner;
    private final DraftSupport.TextState state;
    private byte[] expected;
    private CompletableFuture<?> decompilation;
    private CompletableFuture<?> compilation;
    private CompletableFuture<Boolean> pendingSave;
    private int generation;
    private boolean initialized;
    private boolean loading;
    private String statusKey = "source.exportHint";
    private Throwable lastFailure;

    public DecompileTabPanel(ClassNode classNode) {
        super(classNode);
        owner = JavaOctetEditor.getInstance().getJar();
        setLayout(new BorderLayout());
        codeAreaPanel = new CodeAreaPanel();
        state = new DraftSupport.TextState(codeAreaPanel.getTextArea(), () -> {
            JavaOctetEditor.getInstance().draftsChanged();
            refreshControls();
        });
        KeyStrokeUtil.register(codeAreaPanel.getTextArea(), JavaOctetEditor.getInstance().CONFIG
                .getByClass(ApplicationConfig.class).keymap.getValue().save.getValue(), () -> saveDraft());
        save.setEnabled(false);
        save.addActionListener(event -> saveDraft());
        backendChoice.setSelectedItem(JavaOctetEditor.getInstance().CONFIG
                .getByClass(ApplicationConfig.class).decompilerMode.getValue());
        backendChoice.getAccessibleContext().setAccessibleName(LangUtil.i18n("source.backend"));
        backendChoice.addActionListener(event -> update());
        retry.addActionListener(event -> update());
        cancelDecompilation.addActionListener(event -> {
            if (decompilation != null) decompilation.cancel(true);
        });
        details.addActionListener(event -> {
            if (lastFailure != null) MessageUtil.error(lastFailure);
        });
        JLabel backendLabel = new JLabel(LangUtil.i18n("source.backend"));
        backendLabel.setLabelFor(backendChoice);
        JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        selection.add(backendLabel);
        selection.add(backendChoice);
        selection.add(cancelDecompilation);
        selection.add(retry);
        selection.add(details);
        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.add(selection, BorderLayout.CENTER);
        controls.add(save, BorderLayout.EAST);
        JPanel actions = new JPanel(new BorderLayout(8, 4));
        actions.add(controls, BorderLayout.NORTH);
        actions.add(hint, BorderLayout.CENTER);
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
                : expected != null && !matchesDisplayedClass() ? "draft.classStale" : statusKey));
    }

    private void refreshControls() {
        boolean busy = pendingSave != null && !pendingSave.isDone();
        boolean dirty = hasDraft();
        boolean live = current();
        save.setEnabled(initialized && live && dirty && !busy);
        retry.setEnabled(live && !dirty && !busy && !loading);
        backendChoice.setEnabled(live && !dirty && !busy);
        cancelDecompilation.setVisible(loading);
        cancelDecompilation.setEnabled(loading && decompilation != null && !decompilation.isDone());
        backendChoice.setToolTipText(dirty ? LangUtil.i18n("source.applyBeforeBackend") : null);
        details.setVisible(lastFailure != null);
    }

    private void reportCompilationFailure(Throwable failure, int request, long revision) {
        if (request != generation || !current() || !isDisplayable()) return;
        CompileError error = CompileError.from(failure);
        if (error != null) {
            codeAreaPanel.showCompilationFailure(error, state.revision() == revision);
            statusKey = "source.compileFailed";
            refreshHint();
        } else {
            MessageUtil.error(failure);
        }
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
            long revision = state.revision();
            int request = ++generation;
            if (decompilation != null) decompilation.cancel(true);
            loading = false;
            lastFailure = null;
            statusKey = "source.compiling";
            codeAreaPanel.clearDiagnostics();
            CompletableFuture<Boolean> result = pendingSave = new CompletableFuture<>();
            CompletableFuture<ArchiveCompilation> submitted = app.TASKS.submit(
                    new CompileSourceTask(original.getCanonicalName(), source, baseline),
                    failure -> reportCompilationFailure(failure, request, revision));
            compilation = submitted;
            refreshControls();
            refreshHint();
            result.whenComplete((saved, failure) -> {
                if (result.isCancelled()) submitted.cancel(true);
            });
            submitted.whenComplete((staged, failure) -> DraftSupport.onEdt(() -> {
                boolean committed = false;
                try {
                    if (failure != null || result.isCancelled() || submitted.isCancelled() || request != generation || !current() || !isDisplayable()) return;
                    ArchiveCompilation.Change change = staged.applyTo(owner, submitted::isCancelled);
                    codeAreaPanel.clearDiagnostics();
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
                    statusKey = "source.exportHint";
                    codeAreaPanel.getTextArea().setEditable(initialized);
                    result.complete(committed);
                    refreshHint();
                    refreshControls();
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
        loading = true;
        lastFailure = null;
        statusKey = "source.loading";
        if (decompilation != null && !decompilation.isDone()) decompilation.cancel(true);
        long revision = state.revision();
        CompletableFuture<String> submitted = app.TASKS.submit(new DecompileTask(baseline,
                getClassNode().getInternalName(), (DecompileService.Service) backendChoice.getSelectedItem()),
                failure -> Logger.error(failure));
        decompilation = submitted;
        refreshHint();
        refreshControls();
        submitted.whenComplete((source, failure) -> DraftSupport.onEdt(() -> {
            if (request != generation || !current()) return;
            loading = false;
            if (hasDraft() || state.revision() != revision) {
                statusKey = "source.exportHint";
            } else if (failure != null) {
                lastFailure = submitted.isCancelled() ? null : failure;
                statusKey = submitted.isCancelled() ? "source.cancelled" : "source.failed";
            } else if (!baseline.matches(owner)) {
                statusKey = "source.changed";
            } else {
                expected = displayedClass;
                initialized = true;
                state.replaceClean(source);
                codeAreaPanel.clearDiagnostics();
                codeAreaPanel.getTextArea().setEditable(true);
                statusKey = "source.exportHint";
            }
            refreshHint();
            refreshControls();
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
