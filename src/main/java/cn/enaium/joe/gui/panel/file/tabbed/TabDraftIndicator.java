package cn.enaium.joe.gui.panel.file.tabbed;

import javax.swing.Icon;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Fixed-width tab marker: draft state never changes tab geometry. */
public final class TabDraftIndicator {
    private static final int SIZE = 10;
    private static final Icon CLEAN = new Dot(false);
    private static final Icon DIRTY = new Dot(true);

    private TabDraftIndicator() {
    }

    public static void apply(JTabbedPane tabs, int index, boolean dirty) {
        if (index < 0 || index >= tabs.getTabCount()) return;
        Icon icon = dirty ? DIRTY : CLEAN;
        if (tabs.getIconAt(index) != icon) tabs.setIconAt(index, icon);
    }

    public static boolean isMarked(JTabbedPane tabs, int index) {
        return index >= 0 && index < tabs.getTabCount() && tabs.getIconAt(index) == DIRTY;
    }

    private static final class Dot implements Icon {
        private final boolean filled;

        private Dot(boolean filled) {
            this.filled = filled;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            if (!filled) return;
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = UIManager.getColor("Component.accentColor");
                if (color == null) color = UIManager.getColor("Label.foreground");
                copy.setColor(color == null ? Color.GRAY : color);
                copy.fillOval(x + 2, y + 2, SIZE - 4, SIZE - 4);
            } finally {
                copy.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
