package cn.enaium.joe.gui;

import java.awt.Dimension;
import java.awt.Rectangle;

/** Keeps restored window geometry visible after monitor or DPI changes. */
public final class WindowGeometry {
    private WindowGeometry() {
    }

    public static Rectangle restore(int x, int y, int width, int height, Rectangle virtual, Dimension fallback) {
        if (virtual == null || virtual.width <= 0 || virtual.height <= 0) {
            virtual = new Rectangle(0, 0, Math.max(1, fallback.width), Math.max(1, fallback.height));
        }
        int minWidth = Math.min(320, virtual.width);
        int minHeight = Math.min(240, virtual.height);
        int desiredWidth = width > 0 ? width : fallback.width;
        int desiredHeight = height > 0 ? height : fallback.height;
        int restoredWidth = Math.max(minWidth, Math.min(desiredWidth, virtual.width));
        int restoredHeight = Math.max(minHeight, Math.min(desiredHeight, virtual.height));
        if (width <= 0 || height <= 0) {
            return new Rectangle(virtual.x + (virtual.width - restoredWidth) / 2,
                    virtual.y + (virtual.height - restoredHeight) / 2, restoredWidth, restoredHeight);
        }
        int restoredX = Math.max(virtual.x, Math.min(x, virtual.x + virtual.width - restoredWidth));
        int restoredY = Math.max(virtual.y, Math.min(y, virtual.y + virtual.height - restoredHeight));
        return new Rectangle(restoredX, restoredY, restoredWidth, restoredHeight);
    }
}
