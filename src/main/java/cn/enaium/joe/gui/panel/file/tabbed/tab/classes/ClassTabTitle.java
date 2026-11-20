package cn.enaium.joe.gui.panel.file.tabbed.tab.classes;

/** Class tab labels are always rebuilt from identity, never from decorated UI text. */
final class ClassTabTitle {
    private ClassTabTitle() {
    }

    static String format(String internalName, boolean collision, String defaultPackage,
                         String removedLabel) {
        int separator = internalName.lastIndexOf('/');
        String title = internalName.substring(separator + 1);
        if (collision) {
            String packageName = separator < 0 ? defaultPackage
                    : internalName.substring(0, separator).replace('/', '.');
            title += " (" + packageName + ")";
        }
        if (removedLabel != null) title += " (" + removedLabel + ")";
        return title;
    }
}
