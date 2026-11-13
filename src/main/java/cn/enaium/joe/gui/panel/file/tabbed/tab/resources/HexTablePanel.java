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

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;

/**
 * @author Enaium
 * @since 0.7.0
 */
public class HexTablePanel extends JPanel {
    private static final String[] HEX = new String[256];

    static {
        for (int value = 0; value < HEX.length; value++) HEX[value] = String.format("%02X", value);
    }

    public HexTablePanel(FileTreeNode fileTreeNode) {
        super(new BorderLayout());
        byte[] data = fileTreeNode.getData();
        JTable jTable = new JTable(new AbstractTableModel() {
            @Override public int getRowCount() { return (int) ((data.length + 15L) / 16); }
            @Override public int getColumnCount() { return 16; }
            @Override public String getColumnName(int column) { return Integer.toHexString(column).toUpperCase(java.util.Locale.ROOT); }
            @Override public Object getValueAt(int row, int column) {
                long index = row * 16L + column;
                return index < data.length ? HEX[data[(int) index] & 0xFF] : "";
            }
        });
        jTable.getTableHeader().setReorderingAllowed(false);
        add(new JScrollPane(jTable), BorderLayout.CENTER);

    }
}
