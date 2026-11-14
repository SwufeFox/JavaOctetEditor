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

package cn.enaium.joe.gui.panel.file.tabbed.tab.resources;

import cn.enaium.joe.gui.panel.file.tree.node.FileTreeNode;
import cn.enaium.joe.gui.EntryActions;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.IOUtil;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.Util;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * @author Enaium
 * @since 1.2.0
 */
public class FileTablePane extends JPanel {
    private final Jar archive;
    private final String entryPath;
    private final Object entryLabel;
    private JComponent content;

    public FileTablePane(FileTreeNode fileTreeNode) {
        super(new BorderLayout());
        archive = fileTreeNode.getArchive();
        entryPath = fileTreeNode.getName();
        entryLabel = fileTreeNode.getUserObject();

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JTextField path = new JTextField(entryPath);
        path.setEditable(false);
        path.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        path.setOpaque(false);
        path.setToolTipText(entryPath);
        path.getAccessibleContext().setAccessibleName(LangUtil.i18n("entry.archivePath"));
        header.add(path, BorderLayout.CENTER);

        JPopupMenu actions = new JPopupMenu();
        EntryActions.populatePopup(actions, archive, entryPath, false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        for (Component component : actions.getComponents()) {
            if (component instanceof JMenuItem item) {
                JButton button = new JButton(item.getAction());
                button.setMargin(new Insets(2, 7, 2, 7));
                buttons.add(button);
            }
        }
        header.add(buttons, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        reload();
    }

    public String getEntryPath() {
        return entryPath;
    }

    public Jar getArchive() {
        return archive;
    }

    /** Rebuilds only this resource's view after its caller has obtained draft consent. */
    public void reload() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Resource views must reload on EDT");
        JTextComponent oldText = find(content, JTextComponent.class);
        JScrollPane oldScroll = find(content, JScrollPane.class);
        int caret = oldText == null ? 0 : oldText.getCaretPosition();
        Point position = oldScroll == null ? new Point() : oldScroll.getViewport().getViewPosition();
        Class<?> oldType = content == null ? null : content.getClass();
        if (content != null) remove(content);

        byte[] bytes = archive.getResource(entryPath);
        FileTreeNode node = new FileTreeNode(entryLabel, entryPath, archive);
        if (bytes == null) {
            content = new JLabel(LangUtil.i18n("entry.missing", entryPath), SwingConstants.CENTER);
        } else if (Util.isText(bytes) && isUtf8(bytes)) {
            content = new TextTablePanel(node);
        } else if (IOUtil.isImage(bytes)) {
            content = new ImageTablePanel(node);
        } else {
            content = new HexTablePanel(node);
        }
        add(content, BorderLayout.CENTER);
        revalidate();
        repaint();
        if (oldType == content.getClass()) {
            JComponent current = content;
            SwingUtilities.invokeLater(() -> {
                if (content != current) return;
                JTextComponent text = find(current, JTextComponent.class);
                if (text != null) text.setCaretPosition(Math.min(caret, text.getDocument().getLength()));
                JScrollPane scroll = find(current, JScrollPane.class);
                if (scroll != null) scroll.getViewport().setViewPosition(position);
            });
        }
    }

    private static boolean isUtf8(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate((int) Math.min(bytes.length + 1L, 2048L));
        for (;;) {
            CoderResult result = decoder.decode(input, output, true);
            if (result.isError()) return false;
            if (result.isUnderflow()) return true;
            output.clear();
        }
    }

    private static <T extends Component> T find(Component component, Class<T> type) {
        if (component == null) return null;
        if (type.isInstance(component)) return type.cast(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T match = find(child, type);
                if (match != null) return match;
            }
        }
        return null;
    }
}
