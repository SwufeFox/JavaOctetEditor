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

package cn.enaium.joe.gui.panel.popup;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.annotation.Indeterminate;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.task.AbstractTask;
import cn.enaium.joe.util.Pair;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

/**
 * @author Enaium
 * @since 1.2.0
 */
public class TaskListPopup extends AbstractPopup {
    private final DefaultListModel<Pair<AbstractTask<?>, CompletableFuture<?>>> model = new DefaultListModel<>();
    private final JList<Pair<AbstractTask<?>, CompletableFuture<?>>> list = new JList<>(model);
    private final JButton cancel = new JButton(LangUtil.i18n("tasks.cancelSelected"));
    private final Timer timer = new Timer(250, event -> refreshTasks());

    public TaskListPopup() {
        super(LangUtil.i18n("tasks.show"));
        setPreferredSize(new Dimension(400, 220));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((items, entry, index, selected, focused) -> {
            AbstractTask<?> task = entry.getKey();
            String text = task.getName();
            if (entry.getValue().isCancelled()) text = LangUtil.i18n("tasks.cancelling", task.getName());
            else if (!task.getClass().isAnnotationPresent(Indeterminate.class)) text += " — " + task.getProgress() + "%";
            return new DefaultListCellRenderer().getListCellRendererComponent(items, text, index, selected, focused);
        });
        list.addListSelectionListener(event -> updateCancel());
        cancel.addActionListener(event -> {
            var entry = list.getSelectedValue();
            if (entry != null) entry.getValue().cancel(true);
            refreshTasks();
        });
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(cancel, BorderLayout.SOUTH);
        refreshTasks();
        registerKeyboardAction(event -> destroy(), KeyStroke.getKeyStroke("ESCAPE"), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    private void refreshTasks() {
        var selection = list.getSelectedValue();
        var tasks = JavaOctetEditor.getInstance().TASKS.getTask();
        boolean changed = tasks.size() != model.size();
        for (int index = 0; !changed && index < tasks.size(); index++) {
            changed = tasks.get(index).getKey() != model.get(index).getKey();
        }
        if (changed) {
            model.clear();
            for (var entry : tasks) {
                model.addElement(entry);
                if (selection != null && entry.getKey() == selection.getKey()) list.setSelectedIndex(model.size() - 1);
            }
        }
        list.repaint();
        updateCancel();
    }

    private void updateCancel() {
        var selected = list.getSelectedValue();
        cancel.setEnabled(selected != null && !selected.getValue().isDone());
    }

    @Override
    public void place(int x, int y) {
        super.place(x, y);
        list.requestFocusInWindow();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        refreshTasks();
        timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    @Override
    public void destroy() {
        timer.stop();
        super.destroy();
    }
}
