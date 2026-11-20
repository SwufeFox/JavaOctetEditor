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

package cn.enaium.joe.dialog.search;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.dialog.SearchDialog;
import cn.enaium.joe.util.task.tasks.SearchLdcTask;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.util.StringUtil;
import org.objectweb.asm.tree.LdcInsnNode;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * @author Enaium
 */
public class SearchLdcDialog extends SearchDialog {
    protected JTextField jTextField;
    protected JButton jButton;
    public SearchLdcDialog() {
        setTitle(LangUtil.i18n("search.ldc.title"));
        JPanel criteria = new JPanel(new FlowLayout());
        JTextField text = this.jTextField = new JTextField(15);
        criteria.add(text);
        JButton searchButton = this.jButton = new JButton(LangUtil.i18n("button.search"));
        searchButton.addActionListener(e -> search());
        criteria.add(searchButton);
        add(criteria, BorderLayout.SOUTH);
        text.addActionListener(e -> search());
        getRootPane().setDefaultButton(searchButton);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                text.requestFocusInWindow();
            }
        });
    }

    private void search() {
        String text = jTextField.getText();
        if (StringUtil.isBlank(text)) {
            MessageUtil.warning(LangUtil.i18n("search.pleaseInput"));
            return;
        }
        runSearch(new SearchLdcTask(JavaOctetEditor.getInstance().getJar(), text));
    }

    public SearchLdcDialog(LdcInsnNode ldcInsnNode) {
        this();
        this.jTextField.setText(String.valueOf(ldcInsnNode.cst));
        for(ActionListener listener : this.jButton.getListeners(ActionListener.class)){
            listener.actionPerformed(null);
        }
    }
}
