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

package cn.enaium.joe.gui.component;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.gui.EntryActions;
import cn.enaium.joe.gui.panel.file.tabbed.tab.classes.ClassTabPanel;
import cn.enaium.joe.gui.panel.file.tabbed.tab.resources.FileTablePane;
import cn.enaium.joe.gui.panel.file.tree.FileTreeCellRenderer;
import cn.enaium.joe.gui.panel.file.tree.node.*;
import cn.enaium.joe.jar.ArchiveEntryIndex;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.*;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.util.file.FIleTransferable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Archive navigation. Filtering never creates a second archive or touches open tabs. */
public class FileTree extends JTree {
    public enum PackagePresentation {
        HIERARCHICAL("Hierarchical"), FLAT("Flat");
        private final String value;
        PackagePresentation(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    public static final DefaultTreeNode classesRoot = new DefaultTreeNode("classes");
    public static final DefaultTreeNode resourceRoot = new DefaultTreeNode("resources");

    private Jar archive;
    private ArchiveEntryIndex index;
    private long structuralRevision = Long.MIN_VALUE;
    private PackagePresentation packagePresentation;
    private boolean compactMiddlePackage;
    private String filterQuery = "";
    private long filterGeneration;
    private SwingWorker<List<ArchiveEntryIndex.Entry>, Void> filterWorker;
    private javax.swing.Timer filterDelay;
    private int matchCount;
    private boolean filterBusy;
    private Set<String> unfilteredExpansion;

    public FileTree() {
        super(new DefaultTreeNode(""));
        DefaultTreeNode root = (DefaultTreeNode) getModel().getRoot();
        root.add(classesRoot);
        root.add(resourceRoot);
        setRootVisible(false);
        setShowsRootHandles(true);
        setCellRenderer(new FileTreeCellRenderer());
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2
                        && getPathForLocation(event.getX(), event.getY()) != null) addTab();
            }

            @Override
            public void mousePressed(MouseEvent event) { showPopup(event); }

            @Override
            public void mouseReleased(MouseEvent event) { showPopup(event); }
        });
        KeyStrokeUtil.register(this, JavaOctetEditor.getInstance().CONFIG.getByClass(ApplicationConfig.class)
                .keymap.getValue().copy.getValue(), this::copyFile);
        KeyStrokeUtil.register(this, KeyStroke.getKeyStroke("ENTER"), this::addTab);
        JavaOctetEditor.getInstance().CONFIG.getByClass(ApplicationConfig.class).compactMiddlePackage
                .addListener((instance, oldValue, newValue) -> {
                    if (!Objects.equals(oldValue, newValue)) refresh(archive);
                });
    }

    private void showPopup(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        TreePath path = getPathForLocation(event.getX(), event.getY());
        if (path != null) setSelectionPath(path);
        else clearSelection();
        JPopupMenu popup = new JPopupMenu();
        Object node = path == null ? null : path.getLastPathComponent();
        if (node instanceof ClassTreeNode classNode) {
            EntryActions.populatePopup(popup, classNode.classNode.getInternalName() + ".class", true);
        } else if (node instanceof FileTreeNode fileNode) {
            EntryActions.populatePopup(popup, fileNode.getName(), false);
        } else if (node == null || node == resourceRoot || node instanceof FolderTreeNode) {
            popup.add(EntryActions.createAddResourceItem());
        }
        if (node instanceof DefaultMutableTreeNode treeNode && !treeNode.isLeaf()) {
            if (popup.getComponentCount() > 0) popup.addSeparator();
            JMenuItem expand = new JMenuItem(LangUtil.i18n("popup.fileTree.expandAll"));
            expand.addActionListener(e -> JTreeUtil.setNodeExpandedState(this, treeNode, true));
            popup.add(expand);
        }
        if (popup.getComponentCount() > 0) popup.show(this, event.getX(), event.getY());
    }

    public void addTab() {
        TreePath selected = getSelectionPath();
        if (selected == null) return;
        Object node = selected.getLastPathComponent();
        if (node instanceof ClassTreeNode classNode) {
            if (archive != null && archive.getClassNode(classNode.classNode.getInternalName()) == classNode.classNode)
                openEntry(classNode.classNode.getInternalName() + ".class");
        } else if (node instanceof FileTreeNode fileNode && fileNode.getArchive() == archive) {
            openEntry(fileNode.getName());
        }
    }

    /** Open/reuse an entry in the current archive and reveal it, clearing a hiding filter. EDT only. */
    public boolean openEntry(String path) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Open entries on EDT");
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        Jar current = editor.getJar();
        if (current == null || !editor.isCurrentArchive(current) || path == null) return false;
        ClassNode classNode = path.endsWith(".class")
                ? current.getClassNode(path.substring(0, path.length() - 6)) : null;
        if (classNode == null && !current.getResources().containsKey(path)) return false;
        if (archive != current || structuralRevision != current.getStructuralRevision()) refresh(current);
        TreePath target = findEntry(path, classNode != null);
        if (target != null && classNode != null
                && ((ClassTreeNode) target.getLastPathComponent()).classNode != classNode) target = null;
        if (target == null) {
            if (filterQuery.isEmpty()) applyFilter();
            else setFilterQuery("");
            target = findEntry(path, classNode != null);
        }
        if (target == null) return false;
        setSelectionPath(target);
        scrollPathToVisible(target);
        var tabs = editor.fileTabbedPanel;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            Component component = tabs.getComponentAt(i);
            if (classNode != null && component instanceof ClassTabPanel panel
                    && panel.classNode == classNode) {
                tabs.setSelectedIndex(i);
                return true;
            }
            if (classNode == null && component instanceof FileTablePane panel
                    && panel.getArchive() == current && panel.getEntryPath().equals(path)) {
                tabs.setSelectedIndex(i);
                return true;
            }
        }
        JComponent panel = classNode == null
                ? new FileTablePane((FileTreeNode) target.getLastPathComponent()) : new ClassTabPanel(classNode);
        String title = classNode == null ? path.substring(path.lastIndexOf('/') + 1) : "";
        tabs.addTab(title, panel);
        tabs.setToolTipTextAt(tabs.indexOfComponent(panel), path);
        tabs.setSelectedComponent(panel);
        return true;
    }

    public void copyFile() {
        TreePath selected = getSelectionPath();
        if (selected == null || archive == null || !JavaOctetEditor.getInstance().isCurrentArchive(archive)) return;
        Object node = selected.getLastPathComponent();
        String name;
        byte[] bytes;
        if (node instanceof ClassTreeNode classNode
                && archive.getClassNode(classNode.classNode.getInternalName()) == classNode.classNode) {
            name = classNode.classNode.getSimpleName() + ".class";
            bytes = classNode.classNode.getClassBytes();
        } else if (node instanceof FileTreeNode fileNode && fileNode.getArchive() == archive) {
            name = fileNode.getName().substring(fileNode.getName().lastIndexOf('/') + 1);
            bytes = fileNode.getData();
        } else return;
        if (bytes == null) return;
        try {
            Path directory = Files.createTempDirectory("cn.enaium.joe");
            Path file = directory.resolve(name);
            Files.write(file, bytes);
            directory.toFile().deleteOnExit();
            file.toFile().deleteOnExit();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new FIleTransferable(file.toFile()), null);
        } catch (Exception failure) {
            MessageUtil.error(LangUtil.i18n("navigation.copyFailed"), failure);
        }
    }

    public String getFilterQuery() { return filterQuery; }
    public int getMatchCount() { return matchCount; }
    public boolean isFilterBusy() { return filterBusy; }

    public void setFilterQuery(String query) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Filter entries on EDT");
        String next = Objects.requireNonNullElse(query, "");
        if (next.equals(filterQuery)) return;
        if (filterQuery.isBlank() && !next.isBlank()) unfilteredExpansion = expandedKeys();
        String previous = filterQuery;
        filterQuery = next;
        firePropertyChange("filterQuery", previous, next);
        applyFilter();
    }

    public void refresh(Jar jar) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> refresh(jar));
            return;
        }
        ApplicationConfig config = JavaOctetEditor.getInstance().CONFIG.getByClass(ApplicationConfig.class);
        PackagePresentation presentation = config.packagePresentation.getValue();
        boolean compact = config.compactMiddlePackage.getValue();
        long revision = jar == null ? Long.MIN_VALUE : jar.getStructuralRevision();
        boolean structureChanged = archive != jar || structuralRevision != revision;
        boolean settingsChanged = packagePresentation != presentation || compactMiddlePackage != compact;
        if (!structureChanged && !settingsChanged) {
            repaint();
            return;
        }
        if (archive != jar) unfilteredExpansion = null;
        Jar previousArchive = archive;
        archive = jar;
        structuralRevision = revision;
        packagePresentation = presentation;
        compactMiddlePackage = compact;
        if (structureChanged) index = jar == null ? null : ArchiveEntryIndex.capture(jar);
        applyFilter();
        firePropertyChange("archive", previousArchive, archive);
    }

    private void applyFilter() {
        long generation = ++filterGeneration;
        if (filterWorker != null) filterWorker.cancel(true);
        filterWorker = null;
        if (filterDelay != null) filterDelay.stop();
        filterDelay = null;
        if (archive == null || filterQuery.isBlank()) {
            setBusy(false);
            rebuild(null);
            setMatchCount(archive == null ? 0 : archive.getClassSize()
                    + (int) archive.getResources().keySet().stream().filter(path -> !path.endsWith("/")).count());
            return;
        }
        Jar capturedArchive = archive;
        ArchiveEntryIndex capturedIndex = index;
        String query = filterQuery;
        setBusy(true);
        filterDelay = new javax.swing.Timer(180, event -> {
            filterDelay = null;
            filterWorker = new SwingWorker<>() {
                @Override
                protected List<ArchiveEntryIndex.Entry> doInBackground() {
                    return capturedIndex.matchingEntries(query, EnumSet.allOf(ArchiveEntryIndex.Kind.class));
                }

                @Override
                protected void done() {
                    if (isCancelled() || generation != filterGeneration || archive != capturedArchive
                            || !JavaOctetEditor.getInstance().isCurrentArchive(capturedArchive)) return;
                    filterWorker = null;
                    try {
                        List<ArchiveEntryIndex.Entry> entries = get();
                        Set<String> visible = new HashSet<>();
                        for (ArchiveEntryIndex.Entry entry : entries) visible.add(entry.kind() + ":" + entry.path());
                        rebuild(visible);
                        setMatchCount(entries.size());
                    } catch (CancellationException ignored) {
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException failure) {
                        MessageUtil.error(LangUtil.i18n("navigation.searchFailed"), failure.getCause());
                    } finally {
                        setBusy(false);
                    }
                }
            };
            filterWorker.execute();
        });
        filterDelay.setRepeats(false);
        filterDelay.start();
    }

    private void setMatchCount(int count) {
        int previous = matchCount;
        matchCount = count;
        firePropertyChange("matchCount", previous, count);
    }

    private void setBusy(boolean busy) {
        boolean previous = filterBusy;
        filterBusy = busy;
        firePropertyChange("filterBusy", previous, busy);
    }

    private void rebuild(Set<String> visible) {
        Set<String> expanded = expandedKeys();
        String selected = getSelectionPath() == null ? null : nodeKey(getSelectionPath());
        classesRoot.removeAllChildren();
        resourceRoot.removeAllChildren();
        DefaultTreeModel model = (DefaultTreeModel) getModel();
        if (archive != null) {
            boolean flat = JavaOctetEditor.getInstance().CONFIG.getByClass(ApplicationConfig.class)
                    .packagePresentation.getValue() == PackagePresentation.FLAT;
            Map<String, DefaultTreeNode> packages = new HashMap<>();
            for (ClassNode node : archive.getClasses()) {
                String path = node.getInternalName() + ".class";
                if (visible == null || visible.contains(ArchiveEntryIndex.Kind.CLASS + ":" + path))
                    addLeaf(classesRoot, packages, node.getInternalName(), new ClassTreeNode(node), flat, true);
            }
            packages.clear();
            for (String path : archive.getResources().keySet()) {
                if (path.endsWith("/")) continue;
                if (visible == null || visible.contains(ArchiveEntryIndex.Kind.RESOURCE + ":" + path))
                    addLeaf(resourceRoot, packages, path,
                            new FileTreeNode(path.substring(path.lastIndexOf('/') + 1), path), flat, false);
            }
            if (!flat) {
                compact(model, classesRoot);
                compact(model, resourceRoot);
            }
            sort(model, classesRoot);
            sort(model, resourceRoot);
        }
        model.reload();
        if (filterQuery.isBlank() && unfilteredExpansion != null) {
            expanded = unfilteredExpansion;
            unfilteredExpansion = null;
        }
        Enumeration<?> nodes = ((DefaultMutableTreeNode) model.getRoot()).depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            TreePath path = new TreePath(node.getPath());
            if (visible != null || expanded.contains(nodeKey(path)) || node == classesRoot || node == resourceRoot)
                if (!node.isLeaf()) expandPath(path);
            if (Objects.equals(selected, nodeKey(path))) setSelectionPath(path);
        }
        if (visible != null && getSelectionPath() == null) {
            Enumeration<?> leaves = ((DefaultMutableTreeNode) model.getRoot()).depthFirstEnumeration();
            while (leaves.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) leaves.nextElement();
                if (node instanceof ClassTreeNode || node instanceof FileTreeNode) {
                    setSelectionPath(new TreePath(node.getPath()));
                    break;
                }
            }
        }
    }

    private void addLeaf(DefaultTreeNode root, Map<String, DefaultTreeNode> directories, String path,
                         DefaultTreeNode leaf, boolean flat, boolean classes) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            root.add(leaf);
            return;
        }
        String directory = path.substring(0, slash);
        if (flat) {
            DefaultTreeNode parent = directories.get(directory);
            if (parent == null) {
                parent = classes ? new PackageTreeNode(directory.replace('/', '.'))
                        : new FolderTreeNode(directory.replace('/', '.'));
                directories.put(directory, parent);
                root.add(parent);
            }
            parent.add(leaf);
            return;
        }
        DefaultTreeNode parent = root;
        int start = 0;
        while (start <= slash) {
            int end = path.indexOf('/', start);
            if (end < 0 || end > slash) break;
            String key = path.substring(0, end + 1);
            DefaultTreeNode folder = directories.get(key);
            if (folder == null) {
                String segment = path.substring(start, end);
                folder = classes ? new PackageTreeNode(segment) : new FolderTreeNode(segment);
                directories.put(key, folder);
                parent.add(folder);
            }
            parent = folder;
            start = end + 1;
        }
        parent.add(leaf);
    }

    private TreePath findEntry(String fullPath, boolean classes) {
        Enumeration<?> nodes = (classes ? classesRoot : resourceRoot).depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (classes && node instanceof ClassTreeNode classNode
                    && (classNode.classNode.getInternalName() + ".class").equals(fullPath)
                    || !classes && node instanceof FileTreeNode fileNode && fileNode.getName().equals(fullPath))
                return new TreePath(node.getPath());
        }
        return null;
    }

    private String nodeKey(TreePath path) {
        Object node = path.getLastPathComponent();
        if (node instanceof ClassTreeNode classNode) return "class:" + classNode.classNode.getInternalName();
        if (node instanceof FileTreeNode fileNode) return "resource:" + fileNode.getName();
        return Arrays.toString(path.getPath());
    }

    private Set<String> expandedKeys() {
        Set<String> keys = new HashSet<>();
        Enumeration<TreePath> expanded = getExpandedDescendants(new TreePath(getModel().getRoot()));
        if (expanded != null) while (expanded.hasMoreElements()) keys.add(nodeKey(expanded.nextElement()));
        return keys;
    }

    public void compact(DefaultTreeModel model, DefaultTreeNode node) {
        if (!JavaOctetEditor.getInstance().CONFIG.getByClass(ApplicationConfig.class).compactMiddlePackage.getValue()) return;
        while (node != classesRoot && node != resourceRoot && node.getChildCount() == 1
                && !(node.getChildAt(0) instanceof ClassTreeNode) && !(node.getChildAt(0) instanceof FileTreeNode)) {
            DefaultTreeNode child = (DefaultTreeNode) node.getChildAt(0);
            node.setUserObject(node.getUserObject() + "." + child.getUserObject());
            node.removeAllChildren();
            while (child.getChildCount() > 0) node.add((DefaultTreeNode) child.getChildAt(0));
        }
        for (int i = 0; i < node.getChildCount(); i++) compact(model, (DefaultTreeNode) node.getChildAt(i));
    }

    public void sort(DefaultTreeModel model, DefaultTreeNode node) {
        node.getChildren().sort(Comparator
                .comparing((DefaultTreeNode child) -> child instanceof ClassTreeNode || child instanceof FileTreeNode)
                .thenComparing(Object::toString, String.CASE_INSENSITIVE_ORDER).thenComparing(Object::toString));
        for (int i = 0; i < node.getChildCount(); i++) sort(model, (DefaultTreeNode) node.getChildAt(i));
    }

    @Override
    public void removeNotify() {
        ++filterGeneration;
        if (filterWorker != null) filterWorker.cancel(true);
        filterWorker = null;
        if (filterDelay != null) filterDelay.stop();
        filterDelay = null;
        setBusy(false);
        super.removeNotify();
    }
}
