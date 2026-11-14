package cn.enaium.joe.gui.panel.menu;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.dialog.QuickOpenDialog;
import cn.enaium.joe.util.LangUtil;

import javax.swing.*;
import java.awt.event.KeyEvent;

/** Discoverable archive navigation and its keyboard shortcut. */
public final class NavigationMenu extends JMenu {
    public NavigationMenu() {
        super(LangUtil.i18n("navigation.menu"));
        setMnemonic(KeyEvent.VK_N);
        JMenuItem quickOpen = new JMenuItem(LangUtil.i18n("navigation.quickOpen"));
        quickOpen.setAccelerator(KeyStroke.getKeyStroke("control P"));
        quickOpen.addActionListener(event -> QuickOpenDialog.show(JavaOctetEditor.getInstance()));
        add(quickOpen);
    }
}
