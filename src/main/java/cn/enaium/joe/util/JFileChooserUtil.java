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

package cn.enaium.joe.util;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.util.config.extend.ApplicationConfig;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import java.io.File;

/**
 * @author Enaium
 */
public class JFileChooserUtil {

    private JFileChooserUtil() {
        throw new IllegalAccessError("Utility");
    }

    public static File show(Type type) {
        return show(type, new FileNameExtensionFilter("Zip File(*.zip,*.jar)", "zip", "jar"));
    }

    public static File show(Type type, FileFilter fileFilter) {
        return show(type, fileFilter, JFileChooser.FILES_ONLY);
    }

    public static File show(Type type, FileFilter fileFilter, int mode) {
        return show(type, fileFilter, mode, null);
    }

    public static File show(Type type, FileFilter fileFilter, int mode, File suggested) {
        JFileChooser jFileChooser = new JFileChooser();
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        ApplicationConfig config = editor == null ? null : editor.CONFIG.getByClass(ApplicationConfig.class);
        String remembered = config == null ? null : config.lastDirectory.getValue();
        jFileChooser.setCurrentDirectory(initialDirectory(suggested, remembered,
                FileSystemView.getFileSystemView().getHomeDirectory()));
        jFileChooser.setFileSelectionMode(mode);
        jFileChooser.removeChoosableFileFilter(jFileChooser.getAcceptAllFileFilter());
        jFileChooser.addChoosableFileFilter(fileFilter);
        if (suggested != null) jFileChooser.setSelectedFile(suggested);

        int result = type == Type.OPEN
                ? jFileChooser.showOpenDialog(JavaOctetEditor.getInstance().window)
                : jFileChooser.showSaveDialog(JavaOctetEditor.getInstance().window);
        if (result != JFileChooser.APPROVE_OPTION) return null;
        File selected = jFileChooser.getSelectedFile();
        if (config != null && selected != null) {
            File directory = mode == JFileChooser.DIRECTORIES_ONLY ? selected : selected.getParentFile();
            if (directory != null && directory.isDirectory()) config.lastDirectory.setValue(directory.getAbsolutePath());
        }
        return selected;
    }

    /** Prefer the suggested location, then the last directory, then the chooser fallback. */
    static File initialDirectory(File suggested, String remembered, File fallback) {
        if (suggested != null) {
            File absolute = suggested.getAbsoluteFile();
            File candidate = absolute.isDirectory() ? absolute : absolute.getParentFile();
            if (candidate != null && candidate.isDirectory()) return candidate;
        }
        if (remembered != null && !remembered.isBlank()) {
            File candidate = new File(remembered);
            if (candidate.isDirectory()) return candidate;
        }
        return fallback != null && fallback.isDirectory() ? fallback : new File(".");
    }

    public enum Type {
        OPEN,
        SAVE
    }
}
