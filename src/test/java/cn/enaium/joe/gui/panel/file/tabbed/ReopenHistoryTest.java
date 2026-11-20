package cn.enaium.joe.gui.panel.file.tabbed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReopenHistoryTest {
    @Test
    void keepsOnlyTheTenMostRecentClosedTabs() {
        FileTabbedPanel.ReopenHistory history = new FileTabbedPanel.ReopenHistory();
        for (int i = 0; i < 14; i++) history.record(tab("demo/Type" + i + ".class"));

        assertEquals(10, history.size());
        assertEquals("demo/Type13.class", history.peek().path());

        history.record(tab("resources/config.yml"));
        assertEquals(10, history.size());
        assertEquals("resources/config.yml", history.peek().path());
        assertEquals("demo/Type5.class", oldest(history));
    }

    @Test
    void consumingTheReopenedTabSelectsThePreviousOne() {
        FileTabbedPanel.ReopenHistory history = new FileTabbedPanel.ReopenHistory();
        history.record(tab("demo/First.class"));
        history.record(tab("demo/Second.class"));
        history.consume(history.peek());

        assertEquals(1, history.size());
        assertEquals("demo/First.class", history.peek().path());
    }

    @Test
    void clearDropsEveryClosedTab() {
        FileTabbedPanel.ReopenHistory history = new FileTabbedPanel.ReopenHistory();
        history.record(tab("demo/First.class"));
        history.clear();

        assertTrue(history.isEmpty());
        assertEquals(0, history.size());
    }

    private static String oldest(FileTabbedPanel.ReopenHistory history) {
        String previous = null;
        while (!history.isEmpty()) {
            previous = history.peek().path();
            history.consume(history.peek());
        }
        return previous;
    }

    private static FileTabbedPanel.ClosedTab tab(String path) {
        return new FileTabbedPanel.ClosedTab(null, path);
    }
}
