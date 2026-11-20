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
import cn.enaium.joe.gui.panel.search.ResultNode;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.JMenuUtil;
import cn.enaium.joe.util.KeyStrokeUtil;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.classes.ClassNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * @author Enaium
 */
public class ResultList extends JList<ResultNode> {

    /** Whether a search result can still be opened against the archive loaded now. */
    public enum OpenDecision {
        OPEN, ARCHIVE_CHANGED, MISSING
    }

    public ResultList() {
        super(new DefaultListModel<>());
        //avoid repeat updateFixedCellSize
        setPrototypeCellValue(new ResultNode());
        //fix auto wrap
        setCellRenderer((list, value, index, isSelected, cellHasFocus) -> new JPanel(new BorderLayout()) {{
            if (isSelected) {
                setBackground(list.getSelectionBackground());
            } else {
                setBackground(list.getBackground());
            }
            JLabel comp = new JLabel(value.toString());
            add(comp, BorderLayout.CENTER);
        }});
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 2) return;
                int index = locationToIndex(event.getPoint());
                if (index < 0) return;
                Rectangle bounds = getCellBounds(index, index);
                if (bounds == null || !bounds.contains(event.getPoint())) return;
                setSelectedIndex(index);
                openSelected();
            }
        });
        KeyStrokeUtil.register(this, KeyStroke.getKeyStroke("ENTER"), this::openSelected);
        JPopupMenu jPopupMenu = new JPopupMenu();
        JMenuItem jMenuItem = new JMenuItem(LangUtil.i18n("popup.result.jump"));
        jMenuItem.addActionListener(e -> openSelected());
        jPopupMenu.add(jMenuItem);
        JMenuUtil.addPopupMenu(this, () -> jPopupMenu, () -> this.getSelectedValue() != null);
    }

    /**
     * Checks the result's identity, not just its name, in the current archive. A result from a
     * replaced archive must never open a same-named class from the archive loaded now.
     */
    public static OpenDecision openDecision(Jar current, ClassNode result) {
        if (result == null) return OpenDecision.MISSING;
        String internalName = result.getInternalName();
        if (current == null || internalName == null) return OpenDecision.MISSING;
        ClassNode present = current.getClassNode(internalName);
        if (present == result) return OpenDecision.OPEN;
        return present == null ? OpenDecision.MISSING : OpenDecision.ARCHIVE_CHANGED;
    }

    /** Opens the selection through the tree so hiding filters are cleared and tabs are reused. */
    private void openSelected() {
        ResultNode selected = getSelectedValue();
        if (selected == null || selected.getClassNode() == null) return;
        ClassNode classNode = selected.getClassNode();
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        Jar current = editor == null ? null : editor.getJar();
        switch (openDecision(current, classNode)) {
            case ARCHIVE_CHANGED -> MessageUtil.warning(LangUtil.i18n("search.openStale"));
            case MISSING -> MessageUtil.warning(LangUtil.i18n("search.openMissing"));
            case OPEN -> {
                if (editor.fileTree == null
                        || !editor.fileTree.openEntry(classNode.getInternalName() + ".class")) {
                    MessageUtil.warning(LangUtil.i18n("search.openMissing"));
                }
            }
        }
    }
}
