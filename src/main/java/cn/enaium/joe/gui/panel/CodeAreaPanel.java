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

import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.StringUtil;
import cn.enaium.joe.util.compiler.CompileError;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @author Enaium
 */
public class CodeAreaPanel extends BorderPanel implements ActionListener {

    private final RSyntaxTextArea textArea = new RSyntaxTextArea();
    private final JTextField searchField = new JTextField(30);
    private final JCheckBox regexCB = new JCheckBox(LangUtil.i18n("editor.search.regex"));
    private final JCheckBox matchCaseCB = new JCheckBox(LangUtil.i18n("editor.search.matchCase"));
    private final JToolBar searchBar = new JToolBar();
    private final JLabel searchFeedback = new JLabel();
    private final CompilationDiagnosticsPanel diagnostics = new CompilationDiagnosticsPanel(textArea);
    private final List<Match> matches = new ArrayList<>();
    private boolean searchDirty = true;
    private int lastMatch = -1;
    private String invalidPattern;
    private final DocumentListener documentListener = new DocumentListener() {
        @Override public void insertUpdate(DocumentEvent e) { documentChanged(); }
        @Override public void removeUpdate(DocumentEvent e) { documentChanged(); }
        @Override public void changedUpdate(DocumentEvent e) { documentChanged(); }
    };

    public CodeAreaPanel() {
        textArea.setCodeFoldingEnabled(true);
        Theme theme;
        try {
            theme = Theme.load(getClass().getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/monokai.xml"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        theme.apply(textArea);

        Font defaultFont = RTextArea.getDefaultFont();
        Font uiFont = UIManager.getFont("defaultFont");
        if (uiFont == null) uiFont = UIManager.getFont("TextArea.font");
        Font font = defaultFont.deriveFont(uiFont == null ? defaultFont.getSize2D() : uiFont.getSize2D());
        textArea.setFont(font);
        textArea.setPaintMatchedBracketPair(true);
        textArea.setAnimateBracketMatching(false);

        searchBar.setFloatable(false);
        searchField.getAccessibleContext().setAccessibleName(LangUtil.i18n("editor.search.query"));
        searchBar.add(searchField);
        JButton nextButton = new JButton(LangUtil.i18n("button.findNext"));
        nextButton.setActionCommand("FindNext");
        nextButton.addActionListener(this);
        searchBar.add(nextButton);
        JButton prevButton = new JButton(LangUtil.i18n("button.findPrevious"));
        prevButton.setActionCommand("FindPrev");
        prevButton.addActionListener(this);
        searchBar.add(prevButton);
        searchBar.add(regexCB);
        searchBar.add(matchCaseCB);
        searchBar.addSeparator();
        searchBar.add(searchFeedback);
        searchBar.setVisible(false);
        setTop(searchBar);
        setCenter(new RTextScrollPane(textArea));
        setBottom(diagnostics);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { searchChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { searchChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { searchChanged(); }
        });
        regexCB.addActionListener(e -> searchChanged());
        matchCaseCB.addActionListener(e -> searchChanged());
        textArea.getDocument().addDocumentListener(documentListener);
        textArea.addPropertyChangeListener("document", e -> {
            ((Document) e.getOldValue()).removeDocumentListener(documentListener);
            ((Document) e.getNewValue()).addDocumentListener(documentListener);
            documentChanged();
        });

        bind(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK, "openSearch", this::openSearch);
        bind(textArea, JComponent.WHEN_FOCUSED, KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK, "openSearch", this::openSearch);
        bind(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_ESCAPE, 0, "closeSearch", this::closeSearch);
        bind(textArea, JComponent.WHEN_FOCUSED, KeyEvent.VK_ESCAPE, 0, "closeSearch", this::closeSearch);
        for (JComponent control : new JComponent[]{searchField, nextButton, prevButton, regexCB, matchCaseCB}) {
            bind(control, JComponent.WHEN_FOCUSED, KeyEvent.VK_ESCAPE, 0, "closeSearch", this::closeSearch);
        }
        for (int modifiers : new int[]{0, InputEvent.SHIFT_DOWN_MASK}) {
            boolean forward = modifiers == 0;
            String name = forward ? "findNext" : "findPrevious";
            bind(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_F3, modifiers, name, () -> find(forward));
            bind(textArea, JComponent.WHEN_FOCUSED, KeyEvent.VK_F3, modifiers, name, () -> find(forward));
            bind(searchBar, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyEvent.VK_ENTER, modifiers, name, () -> find(forward));
            for (JComponent control : new JComponent[]{searchField, nextButton, prevButton, regexCB, matchCaseCB}) {
                bind(control, JComponent.WHEN_FOCUSED, KeyEvent.VK_ENTER, modifiers, name, () -> find(forward));
            }
        }
        searchChanged();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        find("FindNext".equals(e.getActionCommand()));
    }

    private static void bind(JComponent component, int condition, int key, int modifiers, String name, Runnable action) {
        component.getInputMap(condition).put(KeyStroke.getKeyStroke(key, modifiers), name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private void openSearch() {
        if (!StringUtil.isBlank(textArea.getSelectedText())) searchField.setText(textArea.getSelectedText());
        searchBar.setVisible(true);
        revalidate();
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    private void closeSearch() {
        searchBar.setVisible(false);
        revalidate();
        textArea.requestFocusInWindow();
    }

    private void documentChanged() {
        diagnostics.invalidateLocations();
        searchChanged();
    }

    private void searchChanged() {
        searchDirty = true;
        lastMatch = -1;
        matches.clear();
        feedback(LangUtil.i18n("editor.search.prompt"));
    }

    private void feedback(String text) {
        searchFeedback.setText(text);
        searchFeedback.setToolTipText(text);
        searchFeedback.getAccessibleContext().setAccessibleName(text);
    }

    private void find(boolean forward) {
        searchBar.setVisible(true);
        revalidate();
        if (searchField.getText().isEmpty()) {
            feedback(LangUtil.i18n("editor.search.prompt"));
            searchField.requestFocusInWindow();
            return;
        }
        if (searchDirty) {
            matches.clear();
            invalidPattern = null;
            try {
                // SearchEngine.find skips zero-length matches and searches substrings, which
                // changes anchor/lookbehind semantics. Match the complete document once per
                // actual query or document change; Matcher.find advances empty matches safely.
                int flags = Pattern.MULTILINE;
                if (!matchCaseCB.isSelected()) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                String query = searchField.getText();
                Pattern pattern = Pattern.compile(regexCB.isSelected() ? query : Pattern.quote(query), flags);
                Matcher matcher = pattern.matcher(textArea.getText());
                while (matcher.find()) matches.add(new Match(matcher.start(), matcher.end()));
            } catch (PatternSyntaxException e) {
                invalidPattern = e.getDescription();
            }
            searchDirty = false;
        }
        if (invalidPattern != null) {
            feedback(LangUtil.i18n("editor.search.invalidRegex", invalidPattern));
            return;
        }
        if (matches.isEmpty()) {
            feedback(LangUtil.i18n("editor.search.noMatches"));
            return;
        }
        int start = textArea.getSelectionStart();
        int end = textArea.getSelectionEnd();
        int index = -1;
        // A remembered empty match must be skipped even though its selection is empty.
        boolean repeated = lastMatch >= 0 && matches.get(lastMatch).start == start && matches.get(lastMatch).end == end;
        if (forward) {
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i).start >= end && !(repeated && i == lastMatch)) { index = i; break; }
            }
        } else {
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i).end <= start && !(repeated && i == lastMatch)) { index = i; break; }
            }
        }
        boolean wrapped = index < 0;
        if (wrapped) index = forward ? 0 : matches.size() - 1;
        Match match = matches.get(index);
        textArea.select(match.start, match.end);
        textArea.getCaret().setSelectionVisible(true);
        lastMatch = index;
        String result = LangUtil.i18n("editor.search.result", index + 1, matches.size());
        if (wrapped) result += " — " + LangUtil.i18n(forward ? "editor.search.wrappedStart" : "editor.search.wrappedEnd");
        feedback(result);
    }

    public void showCompilationFailure(CompileError error, boolean currentSource) {
        diagnostics.showFailure(error, currentSource);
        revalidate();
    }

    public void clearDiagnostics() {
        diagnostics.clear();
        revalidate();
    }

    private record Match(int start, int end) { }

    public RSyntaxTextArea getTextArea() {
        return textArea;
    }
}
