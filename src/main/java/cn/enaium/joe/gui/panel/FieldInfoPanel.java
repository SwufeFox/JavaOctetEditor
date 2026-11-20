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

package cn.enaium.joe.gui.panel;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.dialog.AnnotationListDialog;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.StringUtil;
import cn.enaium.joe.util.event.events.FileTabbedSelectEvent;
import net.miginfocom.swing.MigLayout;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldNode;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;

/**
 * @author Enaium
 * @since 0.6.0
 */
public class FieldInfoPanel extends JPanel {
    private final Jar owner;

    public FieldInfoPanel(FieldNode fieldNode) {
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        owner = app == null ? null : app.getJar();
        setLayout(new MigLayout("fillx", "[fill][fill]"));
        add(new JLabel(LangUtil.i18n("class.info.name")));
        JTextField name = new JTextField(fieldNode.name);
        add(name, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.description")));
        JTextField description = new JTextField(fieldNode.desc);
        add(description, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.access")));
        JTextField access = new JTextField(String.valueOf(fieldNode.access));
        add(access, "wrap");
        add(new JLabel(LangUtil.i18n("class.info.signature")));
        JTextField signature = new JTextField(fieldNode.signature);
        add(signature, "wrap");
        add(new JLabel(LangUtil.i18n("class.annotation.visible")));
        add(annotationButton(fieldNode, owner, true), "wrap");
        add(new JLabel(LangUtil.i18n("class.annotation.invisible")));
        add(annotationButton(fieldNode, owner, false), "wrap");
        JButton save = new JButton(LangUtil.i18n("button.save"));
        save.addActionListener(event -> apply(fieldNode, owner, name, description, access, signature));
        add(save, "span 2");
    }

    private static JButton annotationButton(FieldNode fieldNode, Jar owner, boolean visible) {
        JButton button = new JButton(LangUtil.i18n("button.edit"));
        button.addActionListener(event -> {
            List<AnnotationNode> annotations = visible
                    ? fieldNode.visibleAnnotations
                    : fieldNode.invisibleAnnotations;
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
    private static void apply(FieldNode fieldNode, Jar owner, JTextField name, JTextField description,
                              JTextField access, JTextField signature) {
        JavaOctetEditor app = JavaOctetEditor.getInstance();
        if (app == null || owner == null || !app.isCurrentArchive(owner)) {
            MessageUtil.error(LangUtil.i18n("entry.archiveChanged"));
            return;
        }
        try {
            String nameText = name.getText();
            String newName = StringUtil.isBlank(nameText) ? fieldNode.name : nameText;

            String currentDescription = fieldNode.desc == null ? "" : fieldNode.desc;
            String newDescription = fieldNode.desc;
            if (!Objects.equals(currentDescription, description.getText())) {
                newDescription = MemberMetadata.fieldDescriptor(description.getText());
            }

            int newAccess = fieldNode.access;
            if (!Objects.equals(String.valueOf(fieldNode.access), access.getText())) {
                newAccess = MemberMetadata.access(access.getText());
            }

            String currentSignature = fieldNode.signature == null ? "" : fieldNode.signature;
            String newSignature = fieldNode.signature;
            if (!Objects.equals(currentSignature, signature.getText())) {
                newSignature = MemberMetadata.nullableText(signature.getText());
            }

            boolean changed = !Objects.equals(newName, fieldNode.name)
                    || !Objects.equals(newDescription, fieldNode.desc)
                    || newAccess != fieldNode.access
                    || !Objects.equals(newSignature, fieldNode.signature);
            if (!changed) return;

            fieldNode.name = newName;
            fieldNode.desc = newDescription;
            fieldNode.access = newAccess;
            fieldNode.signature = newSignature;
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
