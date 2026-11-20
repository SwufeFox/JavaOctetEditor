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
import cn.enaium.joe.gui.component.FileTree;
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.gui.editor.EditorDraft;
import cn.enaium.joe.gui.panel.file.tabbed.tab.classes.ClassTabPanel;
import cn.enaium.joe.gui.panel.file.tabbed.tab.resources.FileTablePane;
import cn.enaium.joe.jar.Jar;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
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
    private final ReopenHistory reopenHistory = new ReopenHistory();

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
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isMiddleMouseButton(event) || event.isPopupTrigger()) return;
                int index = tabAt(event.getPoint());
                if (index >= 0) closeTabs(List.of(getComponentAt(index)));
            }
        });
        addChangeListener(e -> JavaOctetEditor.getInstance().EVENTS.call(
                new FileTabbedSelectEvent(getSelectedComponent())));
        JMenuUtil.addPopupMenu(this, () -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem close = new JMenuItem(LangUtil.i18n("popup.tabbed.close"));
            close.addActionListener(event -> {
                if (getSelectedComponent() != null) closeTabs(List.of(getSelectedComponent()));
            });
            menu.add(close);
            JMenuItem reopen = new JMenuItem(LangUtil.i18n("popup.tabbed.reopen"));
            reopen.setEnabled(!reopenHistory.isEmpty());
            reopen.addActionListener(event -> reopenClosedTab());
            menu.add(reopen);
            menu.addSeparator();
            addCloseItem(menu, "popup.tabbed.closeAll", 0);
            addCloseItem(menu, "popup.tabbed.closeOther", 1);
            addCloseItem(menu, "popup.tabbed.closeAllLeft", 2);
            addCloseItem(menu, "popup.tabbed.closeAllRight", 3);
            return menu;
        }, () -> getSelectedComponent() != null);
    }

    private int tabAt(Point point) {
        int index = getUI().tabForCoordinate(this, point.x, point.y);
        return index >= 0 && index < getTabCount() ? index : -1;
    }

    @Override
    public void removeAll() {
        reopenHistory.clear();
        super.removeAll();
    }

    /** Reopens the most recently closed tab when its archive and entry are still present. EDT only. */
    public void reopenClosedTab() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reopenClosedTab);
            return;
        }
        ClosedTab closed = reopenHistory.peek();
        if (closed == null) {
            JavaOctetEditor.getInstance().notifyStatus(LangUtil.i18n("tab.reopenEmpty"));
            return;
        }
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        Jar current = editor.getJar();
        if (current == null || closed.archive() != current || !editor.isCurrentArchive(current)) {
            editor.notifyStatus(LangUtil.i18n("tab.reopenArchiveChanged"));
            return;
        }
        String path = closed.path();
        boolean present = (path.endsWith(".class")
                && current.getClassNode(path.substring(0, path.length() - 6)) != null)
                || current.getResources().containsKey(path);
        if (!present) {
            editor.notifyStatus(LangUtil.i18n("tab.reopenUnavailable"));
            return;
        }
        FileTree tree = editor.fileTree;
        if (tree == null || !tree.openEntry(path)) {
            editor.notifyStatus(LangUtil.i18n("tab.reopenUnavailable"));
            return;
        }
        reopenHistory.consume(closed);
    }

    private ClosedTab describe(Component component) {
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        Jar current = editor.getJar();
        if (current == null || !editor.isCurrentArchive(current)) return null;
        if (component instanceof ClassTabPanel panel) {
            return new ClosedTab(current, panel.getEntryPath());
        }
        if (component instanceof FileTablePane panel && panel.getArchive() == current) {
            return new ClosedTab(current, panel.getEntryPath());
        }
        return null;
    }

    record ClosedTab(Jar archive, String path) {
    }

    static final class ReopenHistory {
        private static final int LIMIT = 10;
        private final ArrayDeque<ClosedTab> entries = new ArrayDeque<>(LIMIT);

        void record(ClosedTab tab) {
            entries.addLast(tab);
            while (entries.size() > LIMIT) entries.removeFirst();
        }

        ClosedTab peek() {
            return entries.peekLast();
        }

        void consume(ClosedTab tab) {
            entries.removeLastOccurrence(tab);
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }

        int size() {
            return entries.size();
        }

        void clear() {
            entries.clear();
        }
    }

    @Override
    public void insertTab(String title, Icon icon, Component component, String tip, int index) {
        super.insertTab(title, icon, component, tip, index);
        int actual = indexOfComponent(component);
        if (actual >= 0) TabDraftIndicator.apply(this, actual, false);
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
                if (index >= 0) {
                    ClosedTab closed = describe(target);
                    removeTabAt(index);
                    if (closed != null) reopenHistory.record(closed);
                }
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
                    TabDraftIndicator.apply(tabs, index, childDirty);
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
