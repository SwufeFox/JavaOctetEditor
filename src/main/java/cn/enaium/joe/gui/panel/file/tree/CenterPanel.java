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

package cn.enaium.joe.gui.panel.file.tree;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.gui.component.RightTabBar;
import cn.enaium.joe.gui.panel.BorderPanel;
import cn.enaium.joe.gui.panel.LeftPanel;
import cn.enaium.joe.gui.panel.file.FileDropTarget;
import cn.enaium.joe.gui.panel.menu.file.LoadMenuItem;
import cn.enaium.joe.gui.panel.menu.file.SaveMenuItem;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.mapping.MappingParser;
import cn.enaium.joe.util.task.tasks.InputJarTask;
import cn.enaium.joe.util.task.tasks.RemappingTask;
import net.fabricmc.mappingio.format.MappingFormat;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.io.File;

/**
 * @author Enaium
 * @since 1.2.0
 */
public class CenterPanel extends BorderPanel {
    private final CenterPane center = new CenterPane();

    public CenterPanel() {
        setCenter(center);
        setRight(new RightPane());
    }

    public void refreshWorkspace() {
        center.refreshWorkspace();
    }

    public static class CenterPane extends JSplitPane{
        private final CardLayout cards = new CardLayout();
        private final JPanel content = new JPanel(cards);
        private final JLabel guidance = new JLabel();
        private final JButton export = new JButton(LangUtil.i18n("menu.file.save"));

        public CenterPane(){
            setLeftComponent(new LeftPanel());
            JPanel empty = new JPanel(new java.awt.GridBagLayout());
            JPanel instructions = new JPanel();
            instructions.setLayout(new BoxLayout(instructions, BoxLayout.Y_AXIS));
            JLabel title = new JLabel(JavaOctetEditor.TITLE);
            title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 22f));
            title.setAlignmentX(0f);
            guidance.setAlignmentX(0f);
            instructions.add(title);
            instructions.add(Box.createVerticalStrut(16));
            instructions.add(guidance);
            instructions.add(Box.createVerticalStrut(16));
            JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            actions.setAlignmentX(0f);
            JButton load = new JButton(LangUtil.i18n("menu.file.load"));
            load.addActionListener(event -> new LoadMenuItem().doClick());
            export.addActionListener(event -> new SaveMenuItem().doClick());
            actions.add(load);
            actions.add(Box.createHorizontalStrut(12));
            actions.add(export);
            instructions.add(actions);
            empty.add(instructions);
            content.add(empty, "empty");
            content.add(JavaOctetEditor.getInstance().fileTabbedPanel, "tabs");
            JavaOctetEditor.getInstance().fileTabbedPanel.addChangeListener(event -> refreshWorkspace());
            setRightComponent(content);
            setResizeWeight(0.2);
            refreshWorkspace();
            setDividerLocation(200);
            new DropTarget(this, DnDConstants.ACTION_COPY_OR_MOVE, new FileDropTarget(
                    (file) -> {
                        if (file.isDirectory()) return true;
                        else {
                            String name = file.getName().toLowerCase();
                            return name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".class") || MappingParser.isAnyMatched(name) != null;
                        }
                    }
                    , files -> {

                if (!files.isEmpty()) {
                    File file = files.getFirst();
                    if (file.isDirectory()) {
                        JavaOctetEditor.getInstance().TASKS.submit(new InputJarTask(file));
                    } else {
                        String name = file.getName().toLowerCase();
                        if (name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".class")){
                            JavaOctetEditor.getInstance().TASKS.submit(new InputJarTask(file));
                        } else {
                            MappingFormat mappingFormat = MappingParser.isAnyMatched(name);
                            if (mappingFormat != null && JavaOctetEditor.getInstance().getJar() != null) {
                                var app = JavaOctetEditor.getInstance();
                                var archive = app.getJar();
                                app.TASKS.submit(new RemappingTask(archive, file, mappingFormat)).thenAccept(staged -> {
                                    app.applyArchiveChange(archive, staged);
                                });
                            }
                        }
                    }
                }
            }), true);
        }

        void refreshWorkspace() {
            boolean loaded = JavaOctetEditor.getInstance().getJar() != null;
            guidance.setText("<html>" + LangUtil.i18n(loaded ? "workspace.browse" : "workspace.open") + "<br><br>"
                    + LangUtil.i18n("workspace.exportHint") + "</html>");
            export.setEnabled(loaded);
            cards.show(content, JavaOctetEditor.getInstance().fileTabbedPanel.getTabCount() == 0 ? "empty" : "tabs");
        }
    }

    public static class RightPane extends BorderPanel{
        public RightPane(){
            JViewport jViewport = new JViewport();
            setCenter(jViewport);
            setRight(new RightTabBar() {{
                addChangeListener(e -> {
                    jViewport.setView(getSelectedComponent());
                });
            }});
        }
    }
}
