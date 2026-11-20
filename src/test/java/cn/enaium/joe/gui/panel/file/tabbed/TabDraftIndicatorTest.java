package cn.enaium.joe.gui.panel.file.tabbed;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabDraftIndicatorTest {
    @Test
    void togglingDraftStateKeepsTheSameIconWidth() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Sample", new JPanel());
        TabDraftIndicator.apply(tabs, 0, false);
        int width = tabs.getIconAt(0).getIconWidth();

        TabDraftIndicator.apply(tabs, 0, true);
        assertTrue(TabDraftIndicator.isMarked(tabs, 0));
        assertEquals(width, tabs.getIconAt(0).getIconWidth());

        TabDraftIndicator.apply(tabs, 0, false);
        assertFalse(TabDraftIndicator.isMarked(tabs, 0));
        assertEquals(width, tabs.getIconAt(0).getIconWidth());
    }
}
