package cn.enaium.joe.gui;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.gui.panel.file.tabbed.tab.resources.FileTablePane;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.jar.ResourceEntryPath;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.task.AbstractTask;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.List;

/** Shared entry actions for the tree and resource editor header. */
public final class EntryActions {
    private EntryActions() {
    }

    public static JMenuItem createAddResourceItem() {
        return new JMenuItem(new AbstractAction(LangUtil.i18n("entry.add")) {
            @Override
            public void actionPerformed(ActionEvent event) {
                perform(() -> addResource(JavaOctetEditor.getInstance()));
            }
        });
    }

    public static void populatePopup(JPopupMenu menu, String fullPath, boolean classEntry) {
        populatePopup(menu, JavaOctetEditor.getInstance().getJar(), fullPath, classEntry);
    }

    /** Binds actions to the archive that owns an already-open resource panel. */
    public static void populatePopup(JPopupMenu menu, Jar archive, String fullPath, boolean classEntry) {
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        menu.add(item("entry.copyPath", () -> {
            capture(editor, archive, fullPath, classEntry);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(fullPath), null);
            editor.notifyStatus(LangUtil.i18n("entry.pathCopied", fullPath));
        }));
        menu.add(item("entry.export", () -> exportEntry(editor, capture(editor, archive, fullPath, classEntry))));
        if (!classEntry) {
            menu.addSeparator();
            menu.add(item("entry.replace", () -> replaceResource(editor, capture(editor, archive, fullPath, false))));
            menu.add(item("entry.delete", () -> deleteResource(editor, capture(editor, archive, fullPath, false))));
        }
    }

    private static JMenuItem item(String key, Runnable action) {
        return new JMenuItem(new AbstractAction(LangUtil.i18n(key)) {
            @Override
            public void actionPerformed(ActionEvent event) {
                perform(action);
            }
        });
    }

    private static String resourcePath(Jar archive, String path) {
        try {
            return ResourceEntryPath.validate(archive, path);
        } catch (ResourceEntryPath.ValidationException invalid) {
            String key = switch (invalid.getReason()) {
                case INVALID_PATH -> "entry.invalidPath";
                case BASE_CLASS -> "entry.baseClassPath";
                case COLLISION -> "entry.pathCollision";
            };
            throw new IllegalArgumentException(LangUtil.i18n(key, invalid.getEntry()));
        }
    }

    private static void perform(Runnable action) {
        try {
            action.run();
        } catch (Exception failure) {
            MessageUtil.error(failure);
        }
    }

    private static void requireArchive(JavaOctetEditor editor, Jar archive) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Entry actions must run on EDT");
        if (archive == null || !editor.isCurrentArchive(archive)) {
            throw new ConcurrentModificationException(LangUtil.i18n("entry.archiveChanged"));
        }
    }

    private static EntryState capture(JavaOctetEditor editor, Jar archive, String path, boolean classEntry) {
        requireArchive(editor, archive);
        byte[] bytes = entryBytes(archive, path, classEntry);
        if (bytes == null) throw new IllegalStateException(LangUtil.i18n("entry.missing", path));
        return new EntryState(archive, path, classEntry, bytes.clone());
    }

    private static byte[] entryBytes(Jar archive, String path, boolean classEntry) {
        if (!classEntry) return archive.getResource(path);
        if (!path.endsWith(".class")) throw new IllegalArgumentException(LangUtil.i18n("entry.missing", path));
        var node = archive.getClassNode(path.substring(0, path.length() - 6));
        return node == null ? null : node.getClassBytes();
    }

    private record EntryState(Jar archive, String path, boolean classEntry, byte[] bytes) {
        void requireCurrent(JavaOctetEditor editor) {
            requireArchive(editor, archive);
            if (!Arrays.equals(bytes, entryBytes(archive, path, classEntry))) {
                throw new ConcurrentModificationException(LangUtil.i18n("entry.changed", path));
            }
        }
    }

    private static File chooseFile(JavaOctetEditor editor, String title, boolean save, String suggestedName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (suggestedName != null) chooser.setSelectedFile(new File(suggestedName));
        int result = save ? chooser.showSaveDialog(editor.window) : chooser.showOpenDialog(editor.window);
        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private static boolean confirm(JavaOctetEditor editor, String key, Object... arguments) {
        return JOptionPane.showConfirmDialog(editor.window, LangUtil.i18n(key, arguments),
                LangUtil.i18n("entry.confirmTitle"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static void exportEntry(JavaOctetEditor editor, EntryState entry) {
        String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
        File selected = chooseFile(editor, LangUtil.i18n("entry.exportTitle", entry.path()), true, name);
        if (selected == null) return;
        entry.requireCurrent(editor);
        Path target = selected.toPath();
        editor.TASKS.submit(new AbstractTask<EntryFileIO.Destination>(LangUtil.i18n("entry.exportTitle", entry.path())) {
            @Override
            public EntryFileIO.Destination get() {
                checkCancelled();
                try {
                    return EntryFileIO.Destination.capture(target);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            protected void onSuccess(EntryFileIO.Destination destination) {
                entry.requireCurrent(editor);
                if (destination.exists() && !confirm(editor, "entry.overwriteFile", destination.path())) return;
                checkCancelled();
                entry.requireCurrent(editor);
                writeExport(editor, entry, destination);
            }
        });
    }

    private static void writeExport(JavaOctetEditor editor, EntryState entry, EntryFileIO.Destination destination) {
        editor.TASKS.submit(new AbstractTask<Boolean>(LangUtil.i18n("entry.exportTitle", entry.path())) {
            @Override
            public Boolean get() {
                try {
                    EntryFileIO.write(destination, entry.bytes(), this::checkCancelled, this::setProgress);
                    return true;
                } catch (IOException failure) {
                    throw new UncheckedIOException(LangUtil.i18n("entry.exportFailed", destination.path()), failure);
                }
            }

            @Override
            protected void onSuccess(Boolean success) {
                editor.notifyStatus(LangUtil.i18n("entry.exported", entry.path(), destination.path()));
            }
        });
    }

    private static void addResource(JavaOctetEditor editor) {
        Jar archive = editor.getJar();
        if (archive == null) {
            MessageUtil.warning(LangUtil.i18n("workspace.open"));
            return;
        }
        requireArchive(editor, archive);
        File selected = chooseFile(editor, LangUtil.i18n("entry.add"), false, null);
        if (selected == null) return;
        requireArchive(editor, archive);
        String path = selected.getName();
        for (;;) {
            path = (String) JOptionPane.showInputDialog(editor.window, LangUtil.i18n("entry.pathPrompt"),
                    LangUtil.i18n("entry.add"), JOptionPane.PLAIN_MESSAGE, null, null, path);
            if (path == null) return;
            requireArchive(editor, archive);
            try {
                path = resourcePath(archive, path);
                break;
            } catch (IllegalArgumentException invalid) {
                MessageUtil.error(invalid.getMessage());
            }
        }
        byte[] original = archive.getResource(path);
        EntryState expected = new EntryState(archive, path, false, original == null ? null : original.clone());
        if (original != null && !confirm(editor, "entry.overwriteResource", path)) return;
        expected.requireCurrent(editor);
        readResource(editor, expected, selected.toPath(), true);
    }

    private static void replaceResource(JavaOctetEditor editor, EntryState entry) {
        File selected = chooseFile(editor, LangUtil.i18n("entry.replaceTitle", entry.path()), false, null);
        if (selected == null) return;
        entry.requireCurrent(editor);
        resourcePath(entry.archive(), entry.path());
        readResource(editor, entry, selected.toPath(), false);
    }

    private static void readResource(JavaOctetEditor editor, EntryState expected, Path source, boolean adding) {
        editor.TASKS.submit(new AbstractTask<byte[]>(LangUtil.i18n("entry.importTitle", expected.path())) {
            @Override
            public byte[] get() {
                try {
                    byte[] bytes = EntryFileIO.read(source, this::checkCancelled);
                    setProgress(100);
                    return bytes;
                } catch (IOException failure) {
                    throw new UncheckedIOException(LangUtil.i18n("entry.importFailed", source), failure);
                }
            }

            @Override
            protected void onSuccess(byte[] bytes) {
                expected.requireCurrent(editor);
                resourcePath(expected.archive(), expected.path());
                if (Arrays.equals(expected.bytes(), bytes)) {
                    editor.notifyStatus(LangUtil.i18n("entry.unchanged", expected.path()));
                    return;
                }
                if (!adding && !confirm(editor, "entry.replaceConfirm", expected.path())) return;
                expected.requireCurrent(editor);
                DraftSupport.Consent consent = editor.confirmDiscardEntryDrafts(expected.path());
                if (consent == null) return;
                checkCancelled();
                expected.requireCurrent(editor);
                if (!consent.isValid()) throw new ConcurrentModificationException(LangUtil.i18n("entry.draftsChanged"));
                resourcePath(expected.archive(), expected.path());
                expected.archive().putResource(expected.path(), bytes);
                finishMutation(editor, expected, false);
                if (adding && editor.fileTree != null) editor.fileTree.openEntry(expected.path());
                editor.notifyStatus(LangUtil.i18n(adding ? "entry.added" : "entry.replaced", expected.path()));
            }
        });
    }

    private static void deleteResource(JavaOctetEditor editor, EntryState entry) {
        if (!confirm(editor, "entry.deleteConfirm", entry.path())) return;
        entry.requireCurrent(editor);
        DraftSupport.Consent consent = editor.confirmDiscardEntryDrafts(entry.path());
        if (consent == null) return;
        entry.requireCurrent(editor);
        if (!consent.isValid()) throw new ConcurrentModificationException(LangUtil.i18n("entry.draftsChanged"));
        entry.archive().removeResource(entry.path());
        finishMutation(editor, entry, true);
        editor.notifyStatus(LangUtil.i18n("entry.deleted", entry.path()));
    }

    private static void finishMutation(JavaOctetEditor editor, EntryState entry, boolean deleted) {
        List<FileTablePane> matching = new ArrayList<>();
        for (Component component : editor.fileTabbedPanel.getComponents()) {
            if (component instanceof FileTablePane pane && pane.getArchive() == entry.archive()
                    && pane.getEntryPath().equals(entry.path())) matching.add(pane);
        }
        for (FileTablePane pane : matching) {
            if (deleted) editor.fileTabbedPanel.remove(pane);
            else pane.reload();
        }
        editor.markArchiveModified();
        editor.refreshJar();
        editor.draftsChanged();
    }
}
