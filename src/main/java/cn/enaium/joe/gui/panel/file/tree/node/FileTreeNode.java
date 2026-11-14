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

package cn.enaium.joe.gui.panel.file.tree.node;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.jar.Jar;

import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Objects;

/**
 * @author Enaium
 */
public class FileTreeNode extends FolderTreeNode {

    private final String file;
    private final Jar archive;
    private byte[] observed;

    public FileTreeNode(Object userObject, String file) {
        this(userObject, file, JavaOctetEditor.getInstance().getJar());
    }

    public FileTreeNode(Object userObject, String file, Jar archive) {
        super(userObject);
        this.file = Objects.requireNonNull(file);
        this.archive = Objects.requireNonNull(archive);
    }

    public String getName(){
        return this.file;
    }

    public Jar getArchive() {
        return archive;
    }

    public byte[] getData() {
        byte[] data = archive.getResource(file);
        if (data == null) throw new ConcurrentModificationException("Resource was removed: " + file);
        observed = data.clone();
        return observed.clone();
    }

    public void setData(byte[] data) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Resource edits must apply on EDT");
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        if (!editor.isCurrentArchive(archive) || observed == null
                || !Arrays.equals(observed, archive.getResource(file))) {
            throw new ConcurrentModificationException("Resource changed or its archive was closed: " + file);
        }
        byte[] owned = Objects.requireNonNull(data).clone();
        archive.putResource(file, owned);
        observed = owned.clone();
        editor.markArchiveModified();
    }
}
