package cn.enaium.joe.gui;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowGeometryTest {
    private static final Dimension FALLBACK = new Dimension(1000, 600);
    private static final Rectangle SCREEN = new Rectangle(0, 0, 1920, 1080);

    @Test
    void firstLaunchCentersTheFallbackSize() {
        assertEquals(new Rectangle(460, 240, 1000, 600),
                WindowGeometry.restore(0, 0, 0, 0, SCREEN, FALLBACK));
    }

    @Test
    void rememberedBoundsArePreserved() {
        assertEquals(new Rectangle(120, 80, 900, 640),
                WindowGeometry.restore(120, 80, 900, 640, SCREEN, FALLBACK));
    }

    @Test
    void offscreenBoundsAreClampedIntoTheVisibleArea() {
        assertEquals(new Rectangle(920, 0, 1000, 600),
                WindowGeometry.restore(5000, -400, 1000, 600, SCREEN, FALLBACK));
    }

    @Test
    void oversizedBoundsFillButNeverExceedTheVisibleArea() {
        assertEquals(new Rectangle(0, 0, 1920, 1080),
                WindowGeometry.restore(5000, -400, 4000, 4000, SCREEN, FALLBACK));
    }

    @Test
    void aSmallerVirtualDesktopStillGetsAUsableWindow() {
        Rectangle small = new Rectangle(0, 0, 300, 200);
        assertEquals(new Rectangle(0, 0, 300, 200),
                WindowGeometry.restore(0, 0, 0, 0, small, FALLBACK));
    }
}
