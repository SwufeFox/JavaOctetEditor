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

package cn.enaium.joe.gui.panel;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.gui.component.FileTree;
import cn.enaium.joe.util.LangUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;

/** Compact archive filter with visible result state and a window-wide focus shortcut. */
public class LeftPanel extends BorderPanel {
    private final FileTree tree = JavaOctetEditor.getInstance().fileTree;
    private final JTextField filter = new JTextField();
    private final JLabel count = new JLabel();

    public LeftPanel() {
        setCenter(new JScrollPane(tree));
        JPanel controls = new JPanel(new BorderLayout(5, 4));
        controls.setBorder(new EmptyBorder(5, 5, 5, 5));
        filter.putClientProperty("JTextField.placeholderText", LangUtil.i18n("navigation.filterPlaceholder"));
        filter.getAccessibleContext().setAccessibleName(LangUtil.i18n("navigation.filter"));
        filter.setToolTipText(LangUtil.i18n("navigation.filterHint"));
        JButton clear = new JButton(LangUtil.i18n("navigation.clear"));
        clear.setToolTipText(LangUtil.i18n("navigation.clearHint"));
        clear.addActionListener(event -> {
            filter.setText("");
            filter.requestFocusInWindow();
        });
        controls.add(filter, BorderLayout.CENTER);
        controls.add(clear, BorderLayout.EAST);
        controls.add(count, BorderLayout.SOUTH);
        setBottom(controls);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
            private void changed() {
                tree.setFilterQuery(filter.getText());
                clear.setEnabled(!filter.getText().isEmpty());
            }
        });
        tree.addPropertyChangeListener(event -> {
            if (event.getPropertyName().equals("filterQuery") && !filter.getText().equals(tree.getFilterQuery()))
                filter.setText(tree.getFilterQuery());
            if (event.getPropertyName().equals("matchCount") || event.getPropertyName().equals("filterBusy")
                    || event.getPropertyName().equals("archive"))
                refreshCount();
        });
        filter.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "clearFilter");
        filter.getActionMap().put("clearFilter", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (filter.getText().isEmpty()) tree.requestFocusInWindow();
                else filter.setText("");
            }
        });
        filter.addActionListener(event -> tree.requestFocusInWindow());
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control L"), "focusArchiveFilter");
        getActionMap().put("focusArchiveFilter", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                filter.requestFocusInWindow();
                filter.selectAll();
            }
        });
        clear.setEnabled(false);
        refreshCount();
    }

    private void refreshCount() {
        if (JavaOctetEditor.getInstance().getJar() == null)
            count.setText(LangUtil.i18n("navigation.noArchive"));
        else if (tree.isFilterBusy()) count.setText(LangUtil.i18n("navigation.searching"));
        else if (tree.getMatchCount() == 0)
            count.setText(LangUtil.i18n(tree.getFilterQuery().isBlank() ? "navigation.noEntries" : "navigation.noMatches"));
        else count.setText(LangUtil.i18n("navigation.matchCount", tree.getMatchCount()));
    }
}
