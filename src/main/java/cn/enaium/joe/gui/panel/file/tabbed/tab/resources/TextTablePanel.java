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

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.gui.panel.BorderPanel;
import cn.enaium.joe.gui.panel.CodeAreaPanel;
import cn.enaium.joe.gui.panel.file.tree.node.FileTreeNode;
import cn.enaium.joe.util.KeyStrokeUtil;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.MessageUtil;
import cn.enaium.joe.gui.editor.EditorDraft;
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.jar.Jar;
import javax.swing.*;
import java.awt.BorderLayout;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * @author Enaium
 * @since 1.2.0
 */
public class TextTablePanel extends BorderPanel implements EditorDraft {
    private final FileTreeNode entry;
    private final Jar archive;
    private final CodeAreaPanel code = new CodeAreaPanel();
    private final DraftSupport.TextState state;
    private final JButton apply = new JButton(LangUtil.i18n("draft.apply"));
    private byte[] expected;
    private boolean validText = true;

    public TextTablePanel(FileTreeNode fileTreeNode) {
        entry = fileTreeNode;
        archive = fileTreeNode.getArchive();
        expected = fileTreeNode.getData().clone();
        String name = fileTreeNode.toString();
        String syntax = SyntaxConstants.SYNTAX_STYLE_NONE;
        for (Map.Entry<String, List<String>> filters : FileTypeUtil.get().getDefaultContentTypeToFilterMap().entrySet()) {
            for (String filter : filters.getValue()) {
                String pattern = filter.replace(".", "\\.").replace("*", ".*").replace("?", ".");
                if (name.matches("(?i)" + pattern)) { syntax = filters.getKey(); break; }
            }
        }
        code.getTextArea().setSyntaxEditingStyle(syntax);
        JLabel hint = new JLabel(LangUtil.i18n("source.exportHint"));
        try {
            code.getTextArea().setText(decodeUtf8(expected));
        } catch (CharacterCodingException malformed) {
            validText = false;
            code.getTextArea().setEditable(false);
            hint.setText(LangUtil.i18n("draft.invalidUtf8"));
        }
        state = new DraftSupport.TextState(code.getTextArea(), () -> {
            apply.setEnabled(validText && hasDraft());
            JavaOctetEditor.getInstance().draftsChanged();
        });
        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.add(hint, BorderLayout.CENTER);
        actions.add(apply, BorderLayout.EAST);
        setTop(actions);
        setCenter(code);
        apply.setEnabled(false);
        apply.addActionListener(event -> saveDraft());
        KeyStrokeUtil.register(code.getTextArea(), JavaOctetEditor.getInstance().CONFIG
                .getByClass(ApplicationConfig.class).keymap.getValue().save.getValue(), () -> saveDraft());
    }

    static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static byte[] encodeUtf8(String text) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    public boolean hasDraft() { return state.isDirty(); }
    public long draftRevision() { return state.revision(); }
    public String draftName() { return entry.getName(); }

    public CompletableFuture<Boolean> saveDraft() {
        return DraftSupport.onEdt(() -> {
            if (!validText) return CompletableFuture.completedFuture(false);
            var app = JavaOctetEditor.getInstance();
            if (!app.isCurrentArchive(archive) || !Arrays.equals(expected, archive.getResource(entry.getName()))) {
                MessageUtil.error(LangUtil.i18n("draft.resourceStale"));
                return CompletableFuture.completedFuture(false);
            }
            if (!hasDraft()) return CompletableFuture.completedFuture(true);
            String content = code.getTextArea().getText();
            try {
                byte[] bytes = encodeUtf8(content);
                entry.setData(bytes);
                expected = bytes.clone();
                state.applied(content);
                app.notifyStatus(LangUtil.i18n("draft.applied"));
                return CompletableFuture.completedFuture(true);
            } catch (CharacterCodingException malformed) {
                MessageUtil.error(LangUtil.i18n("draft.invalidUnicode"));
            } catch (RuntimeException failure) {
                MessageUtil.error(failure);
            }
            return CompletableFuture.completedFuture(false);
        });
    }
}
