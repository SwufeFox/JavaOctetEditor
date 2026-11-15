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

package cn.enaium.joe.gui.panel.menu.file;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.config.value.RecentFilesValue;
import cn.enaium.joe.util.task.AbstractTask;
import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Enaium
 * @since 0.9.0
 */
public class LoadRecentMenu extends JMenu {
    private boolean cleaning;

    public LoadRecentMenu() {
        super(LangUtil.i18n("menu.file.loadRecent"));
        addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                refreshItems();
            }

            @Override
            public void menuDeselected(MenuEvent e) {

            }

            @Override
            public void menuCanceled(MenuEvent e) {

            }
        });
    }

    private RecentFilesValue recentFiles() {
        return JavaOctetEditor.getInstance().CONFIG.getByClass(ApplicationConfig.class).loadRecent;
    }

    private void refreshItems() {
        removeAll();
        var paths = recentFiles().getValue();
        if (paths.isEmpty()) {
            JMenuItem empty = new JMenuItem(LangUtil.i18n("menu.file.recent.empty"));
            empty.setEnabled(false);
            add(empty);
        } else {
            for (String path : paths) {
                JMenuItem item = new JMenuItem(path);
                item.setToolTipText(new File(path).getAbsolutePath());
                item.setIcon(new FlatSVGIcon("icons/jar.svg"));
                item.addActionListener(event -> JavaOctetEditor.getInstance().requestOpenArchive(new File(path)));
                add(item);
            }
        }
        addSeparator();
        JMenuItem cleanup = new JMenuItem(LangUtil.i18n("menu.file.recent.removeMissing"));
        cleanup.setToolTipText(LangUtil.i18n("menu.file.recent.removeMissingHint"));
        cleanup.setEnabled(!cleaning && !paths.isEmpty());
        cleanup.addActionListener(event -> removeMissing());
        add(cleanup);
    }

    private void removeMissing() {
        if (cleaning) return;
        RecentFilesValue recent = recentFiles();
        RecentFilesValue.CleanupSnapshot snapshot = recent.cleanupSnapshot();
        cleaning = true;
        JavaOctetEditor.getInstance().TASKS.submit(new AbstractTask<Set<String>>(
                LangUtil.i18n("menu.file.recent.removeMissing")) {
            @Override
            public Set<String> get() {
                Set<String> missing = new HashSet<>();
                for (String path : snapshot.paths()) {
                    checkCancelled();
                    try {
                        // notExists keeps inaccessible or indeterminate paths in the list.
                        if (Files.notExists(Path.of(path))) missing.add(path);
                    } catch (InvalidPathException invalid) {
                        missing.add(path);
                    } catch (SecurityException inaccessible) {
                        // An access restriction is not proof that a file is missing.
                    }
                }
                checkCancelled();
                return missing;
            }

            @Override
            protected void onSuccess(Set<String> missing) {
                recent.removeMissing(snapshot, missing);
            }
        }).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            cleaning = false;
            if (isSelected()) refreshItems();
        }));
    }
}
