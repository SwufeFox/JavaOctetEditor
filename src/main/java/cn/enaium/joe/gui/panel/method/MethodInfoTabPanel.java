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

package cn.enaium.joe.gui.panel.method;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.dialog.AnnotationListDialog;
import cn.enaium.joe.gui.panel.MemberMetadata;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.StringUtil;
import cn.enaium.joe.util.event.events.FileTabbedSelectEvent;
import net.miginfocom.swing.MigLayout;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;

/**
 * @author Enaium
 * @since 0.6.0
 */
public class MethodInfoTabPanel extends JPanel {
    private final Jar owner;

    public MethodInfoTabPanel(MethodNode methodNode) {
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        owner = app == null ? null : app.getJar();
        setLayout(new MigLayout("fillx", "[fill][fill]"));
        add(new JLabel(LangUtil.i18n("class.info.name")));
        JTextField name = new JTextField(methodNode.name);
        add(name, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.description")));
        JTextField description = new JTextField(methodNode.desc);
        add(description, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.access")));
        JTextField access = new JTextField(String.valueOf(methodNode.access));
        add(access, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.signature")));
        JTextField signature = new JTextField(methodNode.signature);
        add(signature, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.exceptions")));
        JTextField exceptions = new JTextField(MemberMetadata.displayList(methodNode.exceptions));
        add(exceptions, "wrap");

        add(new JLabel(LangUtil.i18n("class.annotation.visible")));
        add(annotationButton(methodNode, owner, true), "wrap");
        add(new JLabel(LangUtil.i18n("class.annotation.invisible")));
        add(annotationButton(methodNode, owner, false), "wrap");
        JButton save = new JButton(LangUtil.i18n("button.save"));
        save.addActionListener(event -> apply(methodNode, owner, name, description, access, signature, exceptions));
        add(save, "span 2");
    }

    private static JButton annotationButton(MethodNode methodNode, Jar owner, boolean visible) {
        JButton button = new JButton(LangUtil.i18n("button.edit"));
        button.addActionListener(event -> {
            List<AnnotationNode> annotations = visible
                    ? methodNode.visibleAnnotations
                    : methodNode.invisibleAnnotations;
            if (annotations == null) return;
            AnnotationListDialog dialog = new AnnotationListDialog(annotations);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    annotationEdited(owner);
                }
            });
            dialog.setVisible(true);
        });
        return button;
    }

    private static void annotationEdited(Jar owner) {
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        if (app == null || owner == null || !app.isCurrentArchive(owner)) return;
        try {
            app.refreshJar();
        } catch (RuntimeException failure) {
            MessageUtil.error(failure);
        }
    }

    /** Validate every edited value before assigning, so a rejected edit never partially mutates the node. */
    private static void apply(MethodNode methodNode, Jar owner, JTextField name, JTextField description,
                              JTextField access, JTextField signature, JTextField exceptions) {
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        if (app == null || owner == null || !app.isCurrentArchive(owner)) {
            MessageUtil.error(LangUtil.i18n("entry.archiveChanged"));
            return;
        }
        try {
            String nameText = name.getText();
            String newName = StringUtil.isBlank(nameText) ? methodNode.name : nameText;

            String currentDescription = methodNode.desc == null ? "" : methodNode.desc;
            String newDescription = methodNode.desc;
            if (!Objects.equals(currentDescription, description.getText())) {
                newDescription = MemberMetadata.methodDescriptor(description.getText());
            }

            int newAccess = methodNode.access;
            if (!Objects.equals(String.valueOf(methodNode.access), access.getText())) {
                newAccess = MemberMetadata.access(access.getText());
            }

            String currentSignature = methodNode.signature == null ? "" : methodNode.signature;
            String newSignature = methodNode.signature;
            if (!Objects.equals(currentSignature, signature.getText())) {
                newSignature = MemberMetadata.nullableText(signature.getText());
            }

            String currentExceptions = MemberMetadata.displayList(methodNode.exceptions);
            List<String> newExceptions = methodNode.exceptions;
            if (!Objects.equals(currentExceptions, exceptions.getText())) {
                newExceptions = MemberMetadata.exceptions(exceptions.getText());
            }

            boolean changed = !Objects.equals(newName, methodNode.name)
                    || !Objects.equals(newDescription, methodNode.desc)
                    || newAccess != methodNode.access
                    || !Objects.equals(newSignature, methodNode.signature)
                    || !Objects.equals(newExceptions, methodNode.exceptions);
            if (!changed) return;

            methodNode.name = newName;
            methodNode.desc = newDescription;
            methodNode.access = newAccess;
            methodNode.signature = newSignature;
            methodNode.exceptions = newExceptions;
            metadataApplied(owner);
        } catch (IllegalArgumentException invalid) {
            MessageUtil.error(invalid.getMessage());
        } catch (RuntimeException failure) {
            MessageUtil.error(failure);
        }
    }

    private static void metadataApplied(Jar owner) {
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        if (app == null || owner == null || !app.isCurrentArchive(owner)) return;
        app.refreshJar();
        app.notifyStatus(LangUtil.i18n("draft.applied"));
        if (app.fileTabbedPanel != null) {
            app.EVENTS.call(new FileTabbedSelectEvent(app.fileTabbedPanel.getSelectedComponent()));
        }
    }
}
