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

import cn.enaium.joe.gui.component.ResultList;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.gui.panel.search.ResultNode;
import cn.enaium.joe.util.task.AbstractTask;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.swing.*;
import java.awt.*;

/**
 * @author Enaium
 */
public class SearchDialog extends Dialog {
    public ResultList resultList = new ResultList();
    private final JLabel status = new JLabel(LangUtil.i18n("search.ready"));
    private CompletableFuture<?> search;

    public SearchDialog() {
        super(LangUtil.i18n("menu.search"));
        setLayout(new BorderLayout());
        setSize(700, 400);
        add(new JScrollPane(resultList), BorderLayout.CENTER);
        add(status, BorderLayout.NORTH);
    }

    protected void runSearch(AbstractTask<List<ResultNode>> task) {
        if (JavaOctetEditor.getInstance().getJar() == null) {
            status.setText(LangUtil.i18n("workspace.open"));
            return;
        }
        if (search != null && !search.isDone()) return;
        var archive = JavaOctetEditor.getInstance().getJar();
        var model = (DefaultListModel<ResultNode>) resultList.getModel();
        model.clear();
        status.setText(LangUtil.i18n("search.running"));
        CompletableFuture<List<ResultNode>> submitted = JavaOctetEditor.getInstance().TASKS.submit(task);
        search = submitted;
        submitted.whenComplete((results, failure) -> {
            if (search != submitted) return;
            if (failure != null) {
                status.setText(LangUtil.i18n(failure instanceof java.util.concurrent.CancellationException ? "search.cancelled" : "search.failed"));
                return;
            }
            if (JavaOctetEditor.getInstance().getJar() != archive) {
                status.setText(LangUtil.i18n("search.archiveChanged"));
                return;
            }
            for (ResultNode result : results) model.addElement(result);
            status.setText(LangUtil.i18n("search.results", results.size()));
        });
    }

    @Override
    public void dispose() {
        if (search != null) search.cancel(true);
        super.dispose();
    }
}
