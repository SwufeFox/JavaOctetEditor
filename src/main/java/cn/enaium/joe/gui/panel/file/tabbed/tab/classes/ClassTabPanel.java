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
import cn.enaium.joe.util.event.events.EditSaveSuccessEvent;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.classes.ClassNode;

import javax.swing.*;
import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;

/**
 * @author Enaium
 */
public class ClassTabPanel extends JPanel {
    public static int classTabIndex = 0;

    protected TraceBytecodeTabPanel traceBytecodeTabPanel;
    protected DecompileTabPanel decompileTabPanel;
    protected ASMifierTablePanel asmTablePanel;
    protected ClassInfoTabPanel classInfoTabPanel;

    private static final LinkedHashMap<String, WeakReference<ClassTabPanel>> internalName2panel = new LinkedHashMap<>();

    public final ClassNode classNode;
    protected final JTabbedPane jTabbedPane;

    public ClassTabPanel(ClassNode classNode) {
        super(new BorderLayout());
        this.classNode = classNode;
        JTabbedPane jTabbedPane = new JTabbedPane();
        jTabbedPane.setTabPlacement(JTabbedPane.BOTTOM);
        jTabbedPane.addTab(LangUtil.i18n("class.tab.bytecodeView"), traceBytecodeTabPanel = new TraceBytecodeTabPanel(classNode));
        jTabbedPane.addTab(LangUtil.i18n("class.tab.decompileView"), decompileTabPanel = new DecompileTabPanel(classNode));
        jTabbedPane.addTab(LangUtil.i18n("class.tab.visitorEdit"), asmTablePanel = new ASMifierTablePanel(classNode));
        jTabbedPane.addTab(LangUtil.i18n("class.tab.infoEdit"), classInfoTabPanel = new ClassInfoTabPanel(classNode));
        jTabbedPane.setSelectedIndex(classTabIndex);
        internalName2panel.entrySet().removeIf(entry -> entry.getValue().get() == null);
        internalName2panel.put(classNode.getInternalName(), new WeakReference<>(this));
        jTabbedPane.addChangeListener(e -> {
            classTabIndex = jTabbedPane.getSelectedIndex();
            for (WeakReference<ClassTabPanel> reference : internalName2panel.values()){
                ClassTabPanel panel = reference.get();
                if (panel != null){
                    panel.jTabbedPane.setSelectedIndex(classTabIndex);
                }
            }
        });
        add(this.jTabbedPane = jTabbedPane);
    }

    public void update(boolean forced){
        if (forced || classTabIndex != 0) traceBytecodeTabPanel.update();
        if (forced || classTabIndex != 1) decompileTabPanel.update();
        if (forced || classTabIndex != 2) asmTablePanel.update();
        if (forced || classTabIndex != 3) classInfoTabPanel.update();
    }

    public static void clearPanels() {
        internalName2panel.clear();
    }

    public static void reconcile(cn.enaium.joe.jar.Jar archive, JTabbedPane tabs) {
        for (int index = tabs.getTabCount() - 1; index >= 0; index--) {
            if (!(tabs.getComponentAt(index) instanceof ClassTabPanel panel)) continue;
            if (archive.getClassNode(panel.classNode.getInternalName()) != panel.classNode) {
                tabs.removeTabAt(index);
            } else {
                tabs.setTitleAt(index, panel.classNode.getSimpleName());
            }
        }
        internalName2panel.entrySet().removeIf(entry -> {
            ClassTabPanel panel = entry.getValue().get();
            return panel == null || archive.getClassNode(panel.classNode.getInternalName()) != panel.classNode;
        });
    }

    static {
        JavaOctetEditor.getInstance().EVENTS.register(EditSaveSuccessEvent.class, event -> {
            SwingUtilities.invokeLater(() -> {
                WeakReference<ClassTabPanel> reference = internalName2panel.remove(event.classInternalName());
                ClassTabPanel panel = reference == null ? null : reference.get();
                if (panel != null) {
                    internalName2panel.put(panel.classNode.getInternalName(), reference);
                    panel.update(false);
                    var app = JavaOctetEditor.getInstance();
                    app.EVENTS.call(new cn.enaium.joe.util.event.events.FileTabbedSelectEvent(app.fileTabbedPanel.getSelectedComponent()));
                }
            });
        });
    }
}