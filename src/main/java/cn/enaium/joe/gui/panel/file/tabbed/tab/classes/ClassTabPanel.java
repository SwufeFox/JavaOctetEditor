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
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.gui.panel.file.tabbed.TabDraftIndicator;
import cn.enaium.joe.jar.Jar;

import javax.swing.*;
import java.awt.*;
import java.lang.ref.WeakReference;
import java.awt.event.HierarchyEvent;
import javax.swing.event.ChangeListener;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

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
    private final Jar owner;
    private final JTextField entryPath = new JTextField();
    private final boolean[] invalidated = {true, true, true, true};
    private JTabbedPane outerTabs;
    private boolean synchronizingSelection;
    private final ChangeListener outerSelectionListener = event -> refreshSelectedView();

    public ClassTabPanel(ClassNode classNode) {
        super(new BorderLayout());
        this.classNode = classNode;
        owner = JavaOctetEditor.getInstance().getJar();
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        entryPath.setEditable(false);
        entryPath.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        entryPath.setOpaque(false);
        entryPath.getAccessibleContext().setAccessibleName(LangUtil.i18n("entry.archivePath"));
        refreshEntryPath();
        header.add(entryPath, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);
        jTabbedPane = new JTabbedPane();
        jTabbedPane.setTabPlacement(JTabbedPane.BOTTOM);
        jTabbedPane.addTab(LangUtil.i18n("class.tab.bytecodeView"), new JPanel());
        jTabbedPane.addTab(LangUtil.i18n("class.tab.decompileView"), new JPanel());
        jTabbedPane.addTab(LangUtil.i18n("class.tab.visitorEdit"), new JPanel());
        jTabbedPane.addTab(LangUtil.i18n("class.tab.infoEdit"), new JPanel());
        for (int index = 0; index < jTabbedPane.getTabCount(); index++) {
            cn.enaium.joe.gui.panel.file.tabbed.TabDraftIndicator.apply(jTabbedPane, index, false);
        }
        jTabbedPane.setSelectedIndex(classTabIndex);
        internalName2panel.entrySet().removeIf(entry -> entry.getValue().get() == null);
        internalName2panel.put(classNode.getInternalName(), new WeakReference<>(this));
        jTabbedPane.addChangeListener(e -> {
            if (synchronizingSelection) return;
            classTabIndex = jTabbedPane.getSelectedIndex();
            for (WeakReference<ClassTabPanel> reference : internalName2panel.values()) {
                ClassTabPanel panel = reference.get();
                if (panel == null) continue;
                panel.synchronizingSelection = true;
                try {
                    panel.jTabbedPane.setSelectedIndex(classTabIndex);
                } finally {
                    panel.synchronizingSelection = false;
                }
                panel.refreshSelectedView();
            }
        });
        add(jTabbedPane);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) == 0) return;
            JTabbedPane parent = getParent() instanceof JTabbedPane tabs ? tabs : null;
            if (outerTabs == parent) return;
            if (outerTabs != null) outerTabs.removeChangeListener(outerSelectionListener);
            outerTabs = parent;
            if (outerTabs != null) outerTabs.addChangeListener(outerSelectionListener);
            refreshSelectedView();
        });
    }

    public void update(boolean forced) {
        refreshEntryPath();
        Arrays.fill(invalidated, true);
        refreshSelectedView();
    }

    public String getEntryPath() {
        return classNode.getInternalName() + ".class";
    }

    private void refreshEntryPath() {
        String path = getEntryPath();
        if (!path.equals(entryPath.getText())) {
            entryPath.setText(path);
            entryPath.setCaretPosition(0);
            entryPath.setToolTipText(path);
        }
    }

    /** Refreshes identity and draft presentation without invalidating any editor views. */
    public static void refreshTabTitles(JTabbedPane tabs) {
        Map<String, Integer> simpleNames = new HashMap<>();
        for (int index = 0; index < tabs.getTabCount(); index++) {
            if (tabs.getComponentAt(index) instanceof ClassTabPanel panel) {
                simpleNames.merge(panel.classNode.getSimpleName(), 1, Integer::sum);
            }
        }
        for (int index = 0; index < tabs.getTabCount(); index++) {
            if (!(tabs.getComponentAt(index) instanceof ClassTabPanel panel)) continue;
            panel.refreshEntryPath();
            boolean removed = !panel.live();
            String title = ClassTabTitle.format(panel.classNode.getInternalName(),
                    simpleNames.get(panel.classNode.getSimpleName()) > 1,
                    LangUtil.i18n("class.tab.defaultPackage"),
                    removed ? LangUtil.i18n("draft.removed") : null);
            if (!title.equals(tabs.getTitleAt(index))) tabs.setTitleAt(index, title);
            TabDraftIndicator.apply(tabs, index, DraftSupport.hasDrafts(panel));
            String tooltip = panel.getEntryPath();
            if (removed) tooltip += " — " + LangUtil.i18n("draft.classRemoved");
            if (!tooltip.equals(tabs.getToolTipTextAt(index))) tabs.setToolTipTextAt(index, tooltip);
        }
    }

    private boolean live() {
        return JavaOctetEditor.getInstance().isCurrentArchive(owner)
                && owner.getClassNode(classNode.getInternalName()) == classNode;
    }

    private void refreshSelectedView() {
        if (outerTabs == null || outerTabs.getSelectedComponent() != this) return;
        int index = jTabbedPane.getSelectedIndex();
        if (index < 0 || !invalidated[index]) return;
        boolean live = live();
        // Removed classes retain existing drafts, but must not create new editors.
        if (!live) {
            if (index == 1 && decompileTabPanel != null) decompileTabPanel.update();
            if (index == 2 && asmTablePanel != null) asmTablePanel.update();
            return;
        }
        invalidated[index] = false;
        Component view;
        switch (index) {
            case 0 -> {
                if (traceBytecodeTabPanel == null) traceBytecodeTabPanel = new TraceBytecodeTabPanel(classNode);
                else traceBytecodeTabPanel.update();
                view = traceBytecodeTabPanel;
            }
            case 1 -> {
                if (decompileTabPanel == null) decompileTabPanel = new DecompileTabPanel(classNode);
                else decompileTabPanel.update();
                view = decompileTabPanel;
            }
            case 2 -> {
                if (asmTablePanel == null) asmTablePanel = new ASMifierTablePanel(classNode);
                else asmTablePanel.update();
                view = asmTablePanel;
            }
            case 3 -> {
                if (classInfoTabPanel == null) classInfoTabPanel = new ClassInfoTabPanel(classNode);
                else classInfoTabPanel.update();
                view = classInfoTabPanel;
            }
            default -> throw new IllegalStateException("Unknown class view: " + index);
        }
        if (jTabbedPane.getComponentAt(index) != view) {
            synchronizingSelection = true;
            try {
                jTabbedPane.setComponentAt(index, view);
            } finally {
                synchronizingSelection = false;
            }
        }
    }

    public static void clearPanels() {
        internalName2panel.clear();
    }

    public static void reconcile(Jar archive, JTabbedPane tabs) {
        for (int index = tabs.getTabCount() - 1; index >= 0; index--) {
            if (!(tabs.getComponentAt(index) instanceof ClassTabPanel panel)) continue;
            boolean live = panel.owner == archive && archive.getClassNode(panel.classNode.getInternalName()) == panel.classNode;
            if (!live && !DraftSupport.hasDrafts(panel)) {
                tabs.removeTabAt(index);
            } else {
                panel.update(false);
            }
        }
        internalName2panel.entrySet().removeIf(entry -> {
            ClassTabPanel panel = entry.getValue().get();
            return panel == null || tabs.indexOfComponent(panel) < 0;
        });
        refreshTabTitles(tabs);
    }

    static {
        JavaOctetEditor.getInstance().EVENTS.register(EditSaveSuccessEvent.class, event -> {
            SwingUtilities.invokeLater(() -> {
                WeakReference<ClassTabPanel> reference = internalName2panel.remove(event.classInternalName());
                ClassTabPanel panel = reference == null ? null : reference.get();
                if (panel != null) {
                    internalName2panel.put(panel.classNode.getInternalName(), reference);
                    panel.update(false);
                    if (panel.outerTabs != null) refreshTabTitles(panel.outerTabs);
                    var app = JavaOctetEditor.getInstance();
                    app.EVENTS.call(new cn.enaium.joe.util.event.events.FileTabbedSelectEvent(app.fileTabbedPanel.getSelectedComponent()));
                }
            });
        });
    }
}