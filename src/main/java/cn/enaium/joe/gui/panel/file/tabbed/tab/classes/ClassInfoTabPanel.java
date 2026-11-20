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
import cn.enaium.joe.dialog.AnnotationListDialog;
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.gui.editor.EditorDraft;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.event.events.EditSaveSuccessEvent;
import net.miginfocom.swing.MigLayout;
import org.benf.cfr.reader.util.StringUtils;
import org.objectweb.asm.tree.AnnotationNode;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * @author Enaium
 * @since 0.6.0
 */
public class ClassInfoTabPanel extends ClassNodeTabPanel implements EditorDraft {
    protected JTextField name, sourceFile, debugFile, access, version, signature, superName, interfaces, outerClass, outerMethod, outerMethodDescription;

    private final Jar owner;
    private final Map<JTextField, String> baseline = new IdentityHashMap<>();
    private final DocumentListener editListener = new DocumentListener() {
        public void insertUpdate(DocumentEvent event) {
            edited();
        }

        public void removeUpdate(DocumentEvent event) {
            edited();
        }

        public void changedUpdate(DocumentEvent event) {
            edited();
        }
    };
    private JTextField[] editors;
    private byte[] expected;
    private long revision;
    private boolean replacing;

    public ClassInfoTabPanel(ClassNode classNode) {
        super(classNode);
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        owner = app == null ? null : app.getJar();
        setLayout(new MigLayout("fillx", "[fill][fill]"));
        add(new JLabel(LangUtil.i18n("class.info.name")));
        JTextField name = this.name = new JTextField(classNode.getInternalName());
        add(name, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.sourceFile")));
        JTextField sourceFile = this.sourceFile = new JTextField(classNode.getNodeInternal().sourceFile);
        add(sourceFile, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.debugFile")));
        JTextField sourceDebug = this.debugFile = new JTextField(classNode.getNodeInternal().sourceDebug);
        add(sourceDebug, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.access")));
        JTextField access = this.access = new JTextField(String.valueOf(classNode.getAccess()));
        add(access, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.version")));
        JTextField version = this.version = new JTextField(String.valueOf(classNode.getVersion()));
        add(version, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.signature")));
        JTextField signature = this.signature = new JTextField(classNode.getNodeInternal().signature);
        add(signature, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.superName")));
        JTextField superName = this.superName = new JTextField(classNode.getSuperClass());
        add(superName, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.interfaces")));
        JTextField interfaces = this.interfaces =  new JTextField(StringUtils.join(classNode.getInterfaces(), ";"));
        add(interfaces, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.outerClass")));
        JTextField outerClass = this.outerClass = new JTextField(classNode.getNodeInternal().outerClass);
        add(outerClass, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.outerMethod")));
        JTextField outerMethod = this.outerMethod = new JTextField(classNode.getNodeInternal().outerMethod);
        add(outerMethod, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.outerMethodDescription")));
        JTextField outerMethodDesc = this.outerMethodDescription = new JTextField(classNode.getNodeInternal().outerMethodDesc);
        add(outerMethodDesc, "wrap");
        add(new JLabel(LangUtil.i18n("class.annotation.visible")));
        add(annotationButton(true), "wrap");
        add(new JLabel(LangUtil.i18n("class.annotation.invisible")));
        add(annotationButton(false), "wrap");
        JButton save = new JButton(LangUtil.i18n("button.save"));
        save.addActionListener(event -> saveDraft());
        add(save, "span 2");

        editors = new JTextField[]{name, sourceFile, debugFile, access, version, signature, superName,
                interfaces, outerClass, outerMethod, outerMethodDescription};
        expected = classNode.getClassBytes().clone();
        captureBaseline();
        for (JTextField editor : editors) editor.getDocument().addDocumentListener(editListener);
    }

    private JButton annotationButton(boolean visible) {
        JButton button = new JButton(LangUtil.i18n("button.edit"));
        button.addActionListener(event -> {
            List<AnnotationNode> annotations = visible
                    ? getClassNode().getClassNode().visibleAnnotations
                    : getClassNode().getClassNode().invisibleAnnotations;
            if (annotations == null) return;
            AnnotationListDialog dialog = new AnnotationListDialog(annotations);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    annotationEdited();
                }
            });
            dialog.setVisible(true);
        });
        return button;
    }

    /** Annotation dialogs mutate the live tree; keep the conflict baseline and archive state in step. */
    private void annotationEdited() {
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        if (app == null || !isLive(app)) return;
        try {
            expected = getClassNode().getClassBytes().clone();
        } catch (RuntimeException failure) {
            MessageUtil.error(failure);
            return;
        }
        app.refreshJar();
    }

    private void edited() {
        revision++;
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        if (!replacing && app != null) app.draftsChanged();
    }

    private void refreshFromNode() {
        var node = getClassNode().getNodeInternal();
        replacing = true;
        try {
            setText(name, getClassNode().getInternalName());
            setText(sourceFile, node.sourceFile);
            setText(debugFile, node.sourceDebug);
            setText(access, String.valueOf(node.access));
            setText(version, String.valueOf(node.version));
            setText(signature, node.signature);
            setText(superName, node.superName);
            setText(interfaces, StringUtils.join(node.interfaces, ";"));
            setText(outerClass, node.outerClass);
            setText(outerMethod, node.outerMethod);
            setText(outerMethodDescription, node.outerMethodDesc);
        } finally {
            replacing = false;
        }
        expected = getClassNode().getClassBytes().clone();
        captureBaseline();
    }

    private static void setText(JTextField field, String value) {
        String text = value == null ? "" : value;
        if (!field.getText().equals(text)) field.setText(text);
    }

    private void captureBaseline() {
        for (JTextField editor : editors) baseline.put(editor, editor.getText());
    }

    private ClassInfoDraft.Values currentValues() {
        return new ClassInfoDraft.Values(name.getText(), sourceFile.getText(), debugFile.getText(),
                access.getText(), version.getText(), signature.getText(), superName.getText(),
                interfaces.getText(), outerClass.getText(), outerMethod.getText(),
                outerMethodDescription.getText());
    }

    private ClassInfoDraft.Values baselineValues() {
        return new ClassInfoDraft.Values(baseline.get(name), baseline.get(sourceFile), baseline.get(debugFile),
                baseline.get(access), baseline.get(version), baseline.get(signature), baseline.get(superName),
                baseline.get(interfaces), baseline.get(outerClass), baseline.get(outerMethod),
                baseline.get(outerMethodDescription));
    }

    private boolean isLive(JavaOctetEditor app) {
        return owner != null && app.isCurrentArchive(owner)
                && owner.getClassNode(getClassNode().getInternalName()) == getClassNode();
    }

    @Override
    public boolean hasDraft() {
        for (JTextField editor : editors) {
            if (!Objects.equals(baseline.get(editor), editor.getText())) return true;
        }
        return false;
    }

    @Override
    public long draftRevision() {
        return revision;
    }

    @Override
    public String draftName() {
        return getClassNode().getInternalName() + ".class / " + LangUtil.i18n("class.tab.infoEdit");
    }

    @Override
    public CompletableFuture<Boolean> saveDraft() {
        return DraftSupport.onEdt(() -> {
            if (!hasDraft()) return CompletableFuture.completedFuture(true);
            JavaOctetEditor app = JavaOctetEditor.getInstance();
            try {
                if (app == null || !isLive(app)) {
                    MessageUtil.error(LangUtil.i18n("draft.classRemoved"));
                    return CompletableFuture.completedFuture(false);
                }
                if (expected == null || !Arrays.equals(expected, getClassNode().getClassBytes())) {
                    MessageUtil.error(LangUtil.i18n("class.info.changed"));
                    return CompletableFuture.completedFuture(false);
                }
                ClassNode staged = ClassInfoDraft.stage(getClassNode(), currentValues(), baselineValues());
                String oldName = getClassNode().getInternalName();
                if (!oldName.equals(staged.getInternalName()) && owner.hasClass(staged.getInternalName())) {
                    MessageUtil.error(LangUtil.i18n("class.info.nameInUse", staged.getInternalName()));
                    return CompletableFuture.completedFuture(false);
                }
                getClassNode().accept(staged);
                refreshFromNode();
                app.draftsChanged();
                app.refreshJar();
                EditSaveSuccessEvent.trigger(oldName);
                app.notifyStatus(LangUtil.i18n("draft.applied"));
                return CompletableFuture.completedFuture(true);
            } catch (ClassInfoDraft.InvalidValueException invalid) {
                MessageUtil.error(LangUtil.i18n(invalid.key()));
                return CompletableFuture.completedFuture(false);
            } catch (RuntimeException failure) {
                MessageUtil.error(failure);
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    public void update() {
        if (hasDraft()) return;
        refreshFromNode();
    }
}
