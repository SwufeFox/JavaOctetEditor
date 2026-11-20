/**
 * Copyright 2022 Enaium
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.enaium.joe.util;

import cn.enaium.joe.dialog.OptionDialog;
import cn.enaium.joe.gui.panel.BorderPanel;
import cn.enaium.joe.gui.panel.confirm.ConfirmPanel;
import org.tinylog.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @author Enaium
 * @since 0.7.0
 */
public class MessageUtil {

    public static void error(String readable) {
        Logger.error(readable);
        onEdt(() -> JOptionPane.showMessageDialog(null, readable, LangUtil.i18n("error"), JOptionPane.ERROR_MESSAGE));
    }

    public static void error(String readable, Throwable e) {
        Logger.error(readable, e);
        onEdt(() -> new OptionDialog(LangUtil.i18n("error"), failurePanel(readable, e),
                JOptionPane.ERROR_MESSAGE).setVisible(true));
    }

    public static void error(Throwable e) {
        Logger.error(e);
        String summary = e.getMessage() == null ? e.toString() : e.getMessage();
        onEdt(() -> new OptionDialog(LangUtil.i18n("error"), failurePanel(summary, e),
                JOptionPane.ERROR_MESSAGE).setVisible(true));
    }

    /** Bounded, copyable failure details instead of an unbounded flat dialog. */
    private static JComponent failurePanel(String summary, Throwable failure) {
        JTextArea message = new JTextArea(summary);
        message.setEditable(false);
        message.setOpaque(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setBorder(null);
        message.setFont(UIManager.getFont("Label.font"));
        message.setColumns(60);
        message.setRows(Math.max(1, Math.min(3, summary.length() / 60 + 1)));
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        JTextArea details = new JTextArea(out.toString());
        details.setEditable(false);
        details.setLineWrap(false);
        details.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(details);
        scroll.setPreferredSize(new Dimension(640, 260));
        JButton copy = new JButton(LangUtil.i18n("error.copyDetails"));
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(out.toString()), null));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(copy);
        BorderPanel panel = new BorderPanel();
        panel.setTop(message);
        panel.setCenter(scroll);
        panel.setBottom(actions);
        return panel;
    }

    public static RuntimeException runtimeException(String readable, Throwable e) {
        error(readable, e);
        return new RuntimeException(readable, e);
    }

    public static RuntimeException runtimeException(Throwable e) {
        error(e);
        return new RuntimeException(e);
    }

    public static void confirm(Object message, String title, Runnable yes, Runnable no) {
        onEdt(() -> new OptionDialog(title, message, JOptionPane.INFORMATION_MESSAGE, yes, no).setVisible(true));
    }

    public static void confirm(ConfirmPanel confirmPanel, String title) {
        confirm(confirmPanel, title, confirmPanel.getConfirm(), confirmPanel.getCancel());
    }

    public static void confirm(ConfirmPanel confirmPanel, String title, Runnable yes) {
        confirm(confirmPanel, title, () -> {
            confirmPanel.getConfirm().run();
            yes.run();
        }, null);
    }

    public static void confirm(Object message, String title, Runnable yes) {
        confirm(message, title, yes, null);
    }


    public static void info(String message) {
        onEdt(() -> new OptionDialog(LangUtil.i18n("info"), message, JOptionPane.INFORMATION_MESSAGE).setVisible(true));
        Logger.info(message);
    }

    public static void warning(String message) {
        onEdt(() -> new OptionDialog(LangUtil.i18n("warning"), message, JOptionPane.WARNING_MESSAGE).setVisible(true));
        Logger.warn(message);
    }

    private static void onEdt(Runnable action) {
        if (GraphicsEnvironment.isHeadless()) return;
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }
}
