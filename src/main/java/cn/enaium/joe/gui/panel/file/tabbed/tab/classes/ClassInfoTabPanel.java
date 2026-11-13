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


import cn.enaium.joe.dialog.AnnotationListDialog;
import cn.enaium.joe.util.event.events.EditSaveSuccessEvent;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.StringUtil;
import cn.enaium.joe.util.classes.ClassNode;
import net.miginfocom.swing.MigLayout;
import org.benf.cfr.reader.util.StringUtils;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * @author Enaium
 * @since 0.6.0
 */
public class ClassInfoTabPanel extends ClassNodeTabPanel {
    protected JTextField name, sourceFile, debugFile, access, version, signature, superName, interfaces, outerClass, outerMethod, outerMethodDescription;

    public ClassInfoTabPanel(ClassNode classNode) {
        super(classNode);
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
        add(new JLabel("Visible Annotation:"));
        add(new JButton(LangUtil.i18n("button.edit")) {{
            addActionListener(e -> {
                if (classNode.getClassNode().visibleAnnotations != null) {
                    new AnnotationListDialog(classNode.getClassNode().visibleAnnotations).setVisible(true);
                }
            });
        }}, "wrap");
        add(new JLabel("Invisible Annotation:"));
        add(new JButton(LangUtil.i18n("button.edit")) {{
            addActionListener(e -> {
                if (classNode.getClassNode().invisibleAnnotations != null) {
                    new AnnotationListDialog(classNode.getClassNode().invisibleAnnotations).setVisible(true);
                }
            });
        }}, "wrap");
        add(new JButton(LangUtil.i18n("button.save")) {{
            addActionListener(e -> {

                try {
                    ClassNode staged = classNode.copy();
                    var node = staged.getClassNode();
                    String oldName = classNode.getInternalName();
                    if (!StringUtil.isBlank(name.getText())) node.name = name.getText().trim();
                    var app = cn.enaium.joe.JavaOctetEditor.getInstance();
                    var archive = app.getJar();
                    if (archive == null || archive.getClassNode(oldName) != classNode) return;
                    if (!node.name.equals(oldName) && archive.hasClass(node.name)) {
                        throw new IllegalArgumentException(LangUtil.i18n("class.info.nameInUse", node.name));
                    }
                    node.sourceFile = nullable(sourceFile.getText());
                    node.sourceDebug = nullable(sourceDebug.getText());
                    if (!StringUtil.isBlank(access.getText())) node.access = Integer.parseInt(access.getText().trim());
                    if (!StringUtil.isBlank(version.getText())) node.version = Integer.parseInt(version.getText().trim());
                    node.signature = nullable(signature.getText());
                    node.superName = nullable(superName.getText());
                    node.interfaces = new ArrayList<>();
                    if (!StringUtil.isBlank(interfaces.getText())) {
                        for (String value : interfaces.getText().split(";")) {
                            if (!value.isBlank()) node.interfaces.add(value.trim());
                        }
                    }
                    node.outerClass = nullable(outerClass.getText());
                    node.outerMethod = nullable(outerMethod.getText());
                    node.outerMethodDesc = nullable(outerMethodDesc.getText());
                    staged.updateBytes();
                    classNode.accept(staged);
                    app.refreshJar();
                    EditSaveSuccessEvent.trigger(oldName);
                    cn.enaium.joe.util.MessageUtil.info(LangUtil.i18n("source.saved"));
                } catch (RuntimeException failure) {
                    cn.enaium.joe.util.MessageUtil.error(failure);
                }
            });
        }}, "span 2");
    }

    private static String nullable(String value) {
        return StringUtil.isBlank(value) ? null : value;
    }

    public void update(){
        this.name.setText(this.getClassNode().getInternalName());
        var node = getClassNode().getNodeInternal();
        this.superName.setText(node.superName);
        this.debugFile.setText(node.sourceDebug);
        this.sourceFile.setText(node.sourceFile);
        this.signature.setText(node.signature);
        this.outerClass.setText(node.outerClass);
        this.outerMethod.setText(node.outerMethod);
        this.outerMethodDescription.setText(node.outerMethodDesc);
        this.access.setText(String.valueOf(node.access));
        this.interfaces.setText(StringUtils.join(node.interfaces, ";"));
        this.version.setText(String.valueOf(node.version));
    }
}
