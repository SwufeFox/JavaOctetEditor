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

import cn.enaium.joe.gui.panel.CodeAreaPanel;
import cn.enaium.joe.util.ASyncUtil;
import cn.enaium.joe.util.classes.ClassNode;
import org.objectweb.asm.util.TraceClassVisitor;

import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @author Enaium
 */
public class TraceBytecodeTabPanel extends ClassNodeTabPanel {
    protected CodeAreaPanel codeAreaPanel;
    private int generation;
    public TraceBytecodeTabPanel(ClassNode classNode) {
        super(classNode);
        setLayout(new BorderLayout());
        CodeAreaPanel codeAreaPanel = this.codeAreaPanel = new CodeAreaPanel();
        codeAreaPanel.getTextArea().setEditable(false);
        codeAreaPanel.getTextArea().setSyntaxEditingStyle("text/custom");
        update();
        add(codeAreaPanel);
    }

    public void update(){
        int request = ++generation;
        ClassNode snapshot = getClassNode().copy();
        final StringWriter stringWriter = new StringWriter();
        ASyncUtil.execute(() -> {
            snapshot.trace(new TraceClassVisitor(new PrintWriter(stringWriter)));
        }, () -> {
            if (request != generation) return;
            if (!java.util.Arrays.equals(snapshot.getClassBytes(), getClassNode().getClassBytes())) return;
            codeAreaPanel.getTextArea().setText(stringWriter.toString());
            codeAreaPanel.getTextArea().setCaretPosition(0);
        });
    }

    @Override
    public void removeNotify() {
        generation++;
        super.removeNotify();
    }
}
