package cn.enaium.joe.gui.panel.file.tabbed.tab.classes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClassTabTitleTest {
    @Test
    void collisionsUseTheWholePackage() {
        assertEquals("Sample (demo)", title("demo/Sample", true, null));
        assertEquals("Sample (other)", title("other/Sample", true, null));
        assertEquals("Sample (a.x)", title("a/x/Sample", true, null));
        assertNotEquals(title("a/x/Sample", true, null),
                title("b/x/Sample", true, null));
    }

    @Test
    void titlesNeverCarryDraftTextBecauseTheIndicatorIsAnIcon() {
        assertEquals("Sample (demo)", title("demo/Sample", true, null));
        assertEquals("Sample", title("demo/Sample", false, null));
    }

    @Test
    void removedDraftsKeepTheirWarning() {
        assertEquals("Sample (demo) (removed)", title("demo/Sample", true, "removed"));
        assertEquals("Renamed (other) (removed)", title("other/Renamed", true, "removed"));
        assertEquals("Renamed (removed)", title("other/Renamed", false, "removed"));
    }

    @Test
    void defaultPackageAndNestedClassNamesRemainDistinct() {
        assertEquals("Sample (default package)", title("Sample", true, null));
        assertEquals("Outer$Sample (demo)", title("demo/Outer$Sample", true, null));
    }

    private static String title(String internalName, boolean collision, String removed) {
        return ClassTabTitle.format(internalName, collision, "default package", removed);
    }
}
