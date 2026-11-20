package cn.enaium.joe.dialog.search;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.dialog.SearchDialog;
import cn.enaium.joe.gui.panel.search.ResultNode;
import cn.enaium.joe.util.task.tasks.SearchOpcodeTask;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.asm.OpcodeUtil;

import javax.swing.*;
import java.awt.*;

public class SearchInstructionDialog extends SearchDialog {
    public SearchInstructionDialog(){
        setTitle(LangUtil.i18n("search.opcode.title"));
        JPanel criteria = new JPanel(new FlowLayout());
        JComboBox<String> opcodeBox = new JComboBox<>(OpcodeUtil.OPCODE.values().toArray(new String[0]));
        criteria.add(opcodeBox);
        JButton search = new JButton(LangUtil.i18n("button.search"));
        search.addActionListener(e -> {
            if (opcodeBox.getSelectedItem() != null) {
                runSearch(new SearchOpcodeTask(JavaOctetEditor.getInstance().getJar(), (String) opcodeBox.getSelectedItem()));
            }
        });
        criteria.add(search);
        add(criteria, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(search);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent event) {
                opcodeBox.requestFocusInWindow();
            }
        });
    }
}
