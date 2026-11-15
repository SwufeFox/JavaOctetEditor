package cn.enaium.joe.gui.panel.file.tabbed.tab.classes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClassTabTitleTest {
    @Test
    void collisionsUseTheWholePackage() {
        assertEquals("Sample (demo)", title("demo/Sample", true, null, false));
        assertEquals("Sample (other)", title("other/Sample", true, null, false));
        assertEquals("Sample (a.x)", title("a/x/Sample", true, null, false));
        assertNotEquals(title("a/x/Sample", true, null, false),
                title("b/x/Sample", true, null, false));
    }

    @Test
    void closingTheOtherTabRestoresTheCompactTitle() {
        assertEquals("Sample (demo) *", title("demo/Sample", true, null, true));
        assertEquals("Sample *", title("demo/Sample", false, null, true));
        assertEquals("Sample", title("demo/Sample", false, null, false));
    }

    @Test
    void removedDraftsKeepTheirWarningAndOneDirtyMarkerAfterRenameOrRefresh() {
        assertEquals("Sample (demo) (removed) *", title("demo/Sample", true, "removed", true));
        assertEquals("Renamed (other) (removed) *", title("other/Renamed", true, "removed", true));
        assertEquals("Renamed (removed)", title("other/Renamed", false, "removed", false));
    }

    @Test
    void defaultPackageAndNestedClassNamesRemainDistinct() {
        assertEquals("Sample (default package)", title("Sample", true, null, false));
        assertEquals("Outer$Sample (demo)", title("demo/Outer$Sample", true, null, false));
    }

    private static String title(String internalName, boolean collision, String removed, boolean dirty) {
        return ClassTabTitle.format(internalName, collision, "default package", removed, dirty);
    }
}
