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

package cn.enaium.joe.gui.panel.file.tabbed;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.util.event.events.FileTabbedSelectEvent;
import cn.enaium.joe.util.JMenuUtil;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.gui.editor.EditorDraft;
import cn.enaium.joe.gui.panel.file.tabbed.tab.classes.ClassTabPanel;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.swing.*;
import java.awt.*;
import java.util.function.BiConsumer;

/**
 * @author Enaium
 */
public class FileTabbedPanel extends JTabbedPane {
    private boolean closing;

    public FileTabbedPanel() {
        setTabLayoutPolicy(SCROLL_TAB_LAYOUT);
        putClientProperty("JTabbedPane.tabClosable", true);
        putClientProperty("JTabbedPane.tabCloseCallback", (BiConsumer<JTabbedPane, Integer>) (tabs, index) -> {
            if (index >= 0 && index < getTabCount()) closeTabs(List.of(getComponentAt(index)));
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "closeDraftTab");
        getActionMap().put("closeDraftTab", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                if (getSelectedComponent() != null) closeTabs(List.of(getSelectedComponent()));
            }
        });
        addChangeListener(e -> JavaOctetEditor.getInstance().EVENTS.call(
                new FileTabbedSelectEvent(getSelectedComponent())));
        JMenuUtil.addPopupMenu(this, () -> {
            JPopupMenu menu = new JPopupMenu();
            addCloseItem(menu, "popup.tabbed.closeAll", 0);
            addCloseItem(menu, "popup.tabbed.closeOther", 1);
            addCloseItem(menu, "popup.tabbed.closeAllLeft", 2);
            addCloseItem(menu, "popup.tabbed.closeAllRight", 3);
            return menu;
        }, () -> getSelectedComponent() != null);
    }

    @Override
    public void insertTab(String title, Icon icon, Component component, String tip, int index) {
        super.insertTab(title, icon, component, tip, index);
        ClassTabPanel.refreshTabTitles(this);
    }

    @Override
    public void removeTabAt(int index) {
        super.removeTabAt(index);
        ClassTabPanel.refreshTabTitles(this);
    }

    private void addCloseItem(JPopupMenu menu, String key, int mode) {
        JMenuItem item = new JMenuItem(LangUtil.i18n(key));
        item.addActionListener(event -> {
            int selected = getSelectedIndex();
            List<Component> targets = new ArrayList<>();
            for (int index = 0; index < getTabCount(); index++) {
                if (mode == 0 || mode == 1 && index != selected
                        || mode == 2 && index < selected || mode == 3 && index > selected) {
                    targets.add(getComponentAt(index));
                }
            }
            closeTabs(targets);
        });
        menu.add(item);
    }

    private void closeTabs(List<Component> targets) {
        if (closing || targets.isEmpty()) return;
        closing = true;
        List<DraftSupport.Consent> consents = new ArrayList<>();
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
        for (Component target : targets) {
            chain = chain.thenCompose(allowed -> DraftSupport.onEdt(() -> {
                if (!allowed) return CompletableFuture.completedFuture(false);
                if (indexOfComponent(target) < 0) return CompletableFuture.completedFuture(true);
                return DraftSupport.confirmClose(target, this).thenApply(consent -> {
                    if (consent == null) return false;
                    consents.add(consent);
                    return true;
                });
            }));
        }
        chain.whenComplete((allowed, failure) -> DraftSupport.onEdt(() -> {
            closing = false;
            if (failure != null || !Boolean.TRUE.equals(allowed)
                    || consents.stream().anyMatch(consent -> !consent.isValid())) return;
            for (Component target : targets) {
                int index = indexOfComponent(target);
                if (index >= 0) removeTabAt(index);
            }
            JavaOctetEditor.getInstance().draftsChanged();
        }));
    }

    public CompletableFuture<Boolean> saveSelectedDrafts() {
        Component selected = getSelectedComponent();
        return selected == null ? CompletableFuture.completedFuture(true) : DraftSupport.saveAll(selected);
    }

    public void refreshDraftIndicators() {
        refreshIndicators(this);
        ClassTabPanel.refreshTabTitles(this);
    }

    private static boolean refreshIndicators(Component root) {
        if (root instanceof EditorDraft draft) return draft.hasDraft();
        boolean dirty = false;
        if (root instanceof JTabbedPane tabs) {
            for (int index = 0; index < tabs.getTabCount(); index++) {
                boolean childDirty = refreshIndicators(tabs.getComponentAt(index));
                if (!(tabs.getComponentAt(index) instanceof ClassTabPanel)) {
                    String title = tabs.getTitleAt(index);
                    boolean marked = title.endsWith(" *");
                    if (childDirty != marked) {
                        tabs.setTitleAt(index, childDirty ? title + " *" : title.substring(0, title.length() - 2));
                    }
                }
                dirty |= childDirty;
            }
        } else if (root instanceof Container container) {
            for (int index = 0; index < container.getComponentCount(); index++) {
                dirty |= refreshIndicators(container.getComponent(index));
            }
        }
        return dirty;
    }
}
