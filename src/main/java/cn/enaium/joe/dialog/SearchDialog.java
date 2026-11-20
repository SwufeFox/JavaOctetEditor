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

package cn.enaium.joe.dialog;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.gui.component.ResultList;
import cn.enaium.joe.gui.panel.search.ResultNode;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.task.AbstractTask;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * @author Enaium
 */
public class SearchDialog extends Dialog {
    public ResultList resultList = new ResultList();
    private final JLabel status = new JLabel(LangUtil.i18n("search.ready"));
    private volatile CompletableFuture<?> search;

    public SearchDialog() {
        super(LangUtil.i18n("menu.search"));
        setLayout(new BorderLayout());
        setSize(700, 400);
        add(new JScrollPane(resultList), BorderLayout.CENTER);
        add(status, BorderLayout.NORTH);
    }

    protected void runSearch(AbstractTask<List<ResultNode>> task) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> runSearch(task));
            return;
        }
        JavaOctetEditor editor = JavaOctetEditor.getInstance();
        if (editor.getJar() == null) {
            status.setText(LangUtil.i18n("workspace.open"));
            return;
        }
        if (search != null && !search.isDone()) {
            status.setText(LangUtil.i18n("search.running"));
            return;
        }
        var archive = editor.getJar();
        var model = (DefaultListModel<ResultNode>) resultList.getModel();
        model.clear();
        status.setToolTipText(null);
        status.setText(LangUtil.i18n("search.running"));
        CompletableFuture<List<ResultNode>> submitted = editor.TASKS.submit(task);
        search = submitted;
        submitted.whenComplete((results, failure) -> {
            if (SwingUtilities.isEventDispatchThread()) {
                publish(submitted, archive, results, failure);
            } else {
                SwingUtilities.invokeLater(() -> publish(submitted, archive, results, failure));
            }
        });
    }

    /** Publishes exactly one outcome for the newest search on the EDT. */
    private void publish(CompletableFuture<List<ResultNode>> submitted, Jar archive,
                         List<ResultNode> results, Throwable failure) {
        if (search != submitted) return;
        search = null;
        var model = (DefaultListModel<ResultNode>) resultList.getModel();
        if (failure != null) {
            boolean cancelled = failure instanceof java.util.concurrent.CancellationException;
            status.setText(LangUtil.i18n(cancelled ? "search.cancelled" : "search.failed"));
            // TaskManager reports the technical cause for genuine failures; cancellation stays silent.
            if (!cancelled) status.setToolTipText(failure.toString());
            return;
        }
        if (!JavaOctetEditor.getInstance().isCurrentArchive(archive)) {
            status.setText(LangUtil.i18n("search.archiveChanged"));
            return;
        }
        if (results != null) for (ResultNode result : results) model.addElement(result);
        status.setText(LangUtil.i18n("search.results", results == null ? 0 : results.size()));
    }

    @Override
    public void dispose() {
        CompletableFuture<?> running = search;
        search = null;
        if (running != null) running.cancel(true);
        super.dispose();
    }
}
