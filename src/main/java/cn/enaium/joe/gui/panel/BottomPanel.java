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
import cn.enaium.joe.annotation.Indeterminate;
import cn.enaium.joe.gui.panel.popup.TaskListPopup;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.task.AbstractTask;
import cn.enaium.joe.util.Pair;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author Enaium
 */
public class BottomPanel extends JPanel {
    private final JLabel archiveStatus = new JLabel();
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton tasksButton = new JButton(LangUtil.i18n("tasks.show"));
    private final JButton cancelButton = new JButton(LangUtil.i18n("tasks.cancelAll"));
    private final TaskListPopup taskListPopup = new TaskListPopup();
    private final Timer timer = new Timer(250, event -> refreshStatus());
    private String notification;
    private long notificationUntil;

    public void notifyStatus(String message) {
        notification = message;
        notificationUntil = System.currentTimeMillis() + 10_000;
        progress.setToolTipText(message);
        refreshStatus();
    }

    public BottomPanel() {
        super(new GridBagLayout());
        setBorder(new EmptyBorder(5, 8, 5, 8));
        progress.setStringPainted(true);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(tasksButton);
        actions.add(cancelButton);

        // Fixed-weight columns: changing status, progress text or task count cannot move its neighbors.
        int rowHeight = Math.max(progress.getPreferredSize().height,
                archiveStatus.getFontMetrics(archiveStatus.getFont()).getHeight());
        archiveStatus.setPreferredSize(new Dimension(0, rowHeight));
        archiveStatus.setMinimumSize(new Dimension(0, rowHeight));
        archiveStatus.setVerticalAlignment(SwingConstants.CENTER);
        progress.setPreferredSize(new Dimension(0, progress.getPreferredSize().height));
        progress.setMinimumSize(new Dimension(0, progress.getPreferredSize().height));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.gridx = 0;
        constraints.weightx = 0.38;
        constraints.insets = new Insets(0, 0, 0, 12);
        add(archiveStatus, constraints);
        constraints.gridx = 1;
        constraints.weightx = 0.62;
        constraints.insets = new Insets(0, 0, 0, 12);
        add(progress, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        constraints.insets = new Insets(0, 0, 0, 0);
        add(actions, constraints);
        tasksButton.addActionListener(event -> {
            Point location = tasksButton.getLocationOnScreen();
            taskListPopup.place(location.x - taskListPopup.getPreferredSize().width + tasksButton.getWidth(),
                    location.y - taskListPopup.getPreferredSize().height);
        });
        cancelButton.addActionListener(event -> JavaOctetEditor.getInstance().TASKS.cancelAll());
        refreshStatus();
        Dimension taskSize = tasksButton.getPreferredSize();
        tasksButton.setPreferredSize(new Dimension(taskSize.width + 18, taskSize.height));
    }

    public void refreshStatus() {
        var app = JavaOctetEditor.getInstance();
        var archive = app.getJar();
        String state = app.hasArchiveChanges() ? LangUtil.i18n("document.modified") : LangUtil.i18n("document.clean");
        if (app.hasUnappliedDrafts()) state += ", " + LangUtil.i18n("document.drafts");
        String status = archive == null ? LangUtil.i18n("workspace.noArchive")
                : LangUtil.i18n("workspace.archiveStatus", archive.getClassSize(), archive.getResourceSize()) + " — " + state;
        archiveStatus.setText(status);
        String path = app.getArchiveSource() == null ? null : app.getArchiveSource().getAbsolutePath();
        archiveStatus.setToolTipText(path == null ? status : status + " — " + path);
        List<Pair<AbstractTask<?>, CompletableFuture<?>>> tasks = app.TASKS.getTask();
        cancelButton.setEnabled(tasks.stream().anyMatch(entry -> !entry.getValue().isDone()));
        tasksButton.setText(LangUtil.i18n("tasks.show") + " (" + (tasks.size() > 99 ? "99+" : tasks.size()) + ")");
        if (tasks.isEmpty()) {
            progress.setIndeterminate(false);
            progress.setValue(0);
            progress.setString(notification != null && System.currentTimeMillis() < notificationUntil
                    ? notification : LangUtil.i18n("tasks.ready"));
        } else {
            var entry = tasks.getLast();
            AbstractTask<?> task = entry.getKey();
            boolean cancelling = entry.getValue().isCancelled();
            boolean indeterminate = cancelling || task.getClass().isAnnotationPresent(Indeterminate.class);
            progress.setIndeterminate(indeterminate);
            progress.setValue(task.getProgress());
            progress.setString(cancelling ? LangUtil.i18n("tasks.cancelling", task.getName())
                    : task.getName() + (indeterminate ? "" : " — " + task.getProgress() + "%"));
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        timer.start();
    }

    @Override
    public void removeNotify() {
        shutdown();
        super.removeNotify();
    }

    public void shutdown() {
        timer.stop();
        taskListPopup.destroy();
    }
}
