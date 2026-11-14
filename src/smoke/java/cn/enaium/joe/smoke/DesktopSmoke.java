package cn.enaium.joe.smoke;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.dialog.QuickOpenDialog;
import cn.enaium.joe.gui.EntryActions;
import cn.enaium.joe.gui.panel.file.tabbed.tab.classes.DecompileTabPanel;
import cn.enaium.joe.jar.ArchiveIO;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.classes.ClassNode;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

/** Explicit graphical checks against the packaged application, with an isolated working directory. */
public final class DesktopSmoke {
    private static JavaOctetEditor editor;
    private static Path evidence;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the isolated smoke working directory");
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("desktopSmoke requires a graphical desktop session");
        evidence = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(evidence);
        if (!Files.isSameFile(evidence, Path.of("").toAbsolutePath())) {
            throw new IllegalArgumentException("Run smoke from its evidence directory so application settings stay isolated");
        }
        try {
            Path input = evidence.resolve("input.jar");
            Jar fixture = new Jar();
            fixture.putClassNode(ClassNode.of(classBytes("demo/Sample", 1)));
            fixture.putClassNode(ClassNode.of(classBytes("other/Sample", 2)));
            fixture.putResource("config/app.properties", "answer=1\n".getBytes(StandardCharsets.UTF_8));
            fixture.putResource("assets/binary.bin", new byte[] { 0, -1, 42, 7 });
            ArchiveIO.write(fixture, input);
            byte[] inputBefore = Files.readAllBytes(input);
            edt(() -> {
                editor = new JavaOctetEditor();
                editor.run();
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                editor.window.setSize(Math.min(1100, screen.width - 80), Math.min(760, screen.height - 80));
                editor.window.setLocationRelativeTo(null);
                editor.requestOpenArchive(input.toFile());
                return null;
            });
            await(() -> editor.getJar() != null, "initial archive load");
            check(edt(() -> editor.getArchiveSource().equals(input.toFile())), "source path accepted after load");
            check(!edt(() -> editor.hasArchiveChanges()), "loaded archive starts clean");

            edt(() -> { editor.fileTree.openEntry("demo/Sample.class"); return null; });
            Component classTab = edt(() -> editor.fileTabbedPanel.getSelectedComponent());
            DecompileTabPanel sourcePanel = edt(() -> find(classTab, DecompileTabPanel.class, item -> true));
            RSyntaxTextArea source = edt(() -> find(sourcePanel, RSyntaxTextArea.class, item -> true));
            edt(() -> { ((JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, sourcePanel)).setSelectedComponent(sourcePanel); return null; });
            await(() -> source.isEditable() && source.getText().contains("class Sample"), "source decompilation");
            String validSource = "package demo; public class Sample { public static int value() { return 9; } }";
            CompletableFuture<Boolean> capturedCompile = edt(() -> {
                source.setText("package demo; public class Sample { public static int value() { return 7; } }");
                CompletableFuture<Boolean> pending = sourcePanel.saveDraft();
                source.setText(validSource);
                return pending;
            });
            check(capturedCompile.get(30, TimeUnit.SECONDS), "captured source compilation succeeds");
            check(edt(() -> sourcePanel.hasDraft() && source.getText().equals(validSource)), "newer source text survives background compile");
            check(value(edt(() -> editor.getJar().getClassNode("demo/Sample").getClassBytes().clone())) == 7, "compile applies captured source only");
            CompletableFuture<Boolean> compiled = edt(() -> sourcePanel.saveDraft());
            check(compiled.get(30, TimeUnit.SECONDS), "newer draft compilation succeeds");
            check(edt(() -> editor.hasArchiveChanges()), "applied source requires export");
            byte[] compiledBytes = edt(() -> editor.getJar().getClassNode("demo/Sample").getClassBytes().clone());
            check((compiledBytes[6] & 255) * 256 + (compiledBytes[7] & 255) == 52, "Java 8 target remains 52");
            check(value(compiledBytes) == 9, "applied class executes new value");
            await(() -> !sourcePanel.hasDraft() && source.isEditable(), "applied source baseline");
            screenshot("source-after-compile.png");

            edt(() -> { editor.fileTree.openEntry("config/app.properties"); return null; });
            Component resourceTab = edt(() -> editor.fileTabbedPanel.getSelectedComponent());
            int opened = edt(() -> editor.fileTabbedPanel.getTabCount());
            edt(() -> { editor.fileTree.openEntry("config/app.properties"); return null; });
            check(edt(() -> editor.fileTabbedPanel.getTabCount()) == opened, "resource navigation reuses tab");
            RSyntaxTextArea resource = edt(() -> find(resourceTab, RSyntaxTextArea.class, item -> true));
            edt(() -> { resource.setText("answer=9\n"); return null; });
            check(edt(() -> new String(editor.getJar().getResource("config/app.properties"), StandardCharsets.UTF_8).equals("answer=1\n")), "draft does not mutate resource before apply");
            fireCloseTab();
            chooseOption(2);
            check(edt(() -> editor.fileTabbedPanel.indexOfComponent(resourceTab)) >= 0, "cancel close retains tab");
            check(edt(() -> resource.getText().equals("answer=9\n")), "cancel close retains text");
            screenshot("resource-draft.png");
            fireCloseTab();
            chooseOption(0);
            await(() -> editor.fileTabbedPanel.indexOfComponent(resourceTab) < 0, "apply-and-close resource");
            check(edt(() -> Arrays.equals(editor.getJar().getResource("config/app.properties"), "answer=9\n".getBytes(StandardCharsets.UTF_8))), "apply-and-close commits resource");

            pressKey(source, KeyEvent.VK_P, true);
            await(() -> visibleWindow(QuickOpenDialog.class) != null, "quick open dialog");
            QuickOpenDialog quick = edt(() -> visibleWindow(QuickOpenDialog.class));
            JTextField query = edt(() -> find(quick, JTextField.class, item -> true));
            edt(() -> { query.setText("other.Sample"); return null; });
            await(() -> { JList<?> list = find(quick, JList.class, item -> true); return list != null && list.getModel().getSize() == 1; }, "ranked dotted-name search");
            screenshot("quick-open.png");
            pressKey(query, KeyEvent.VK_ENTER, false);
            await(() -> !quick.isShowing(), "quick open selection");
            check(edt(() -> editor.fileTabbedPanel.getTabCount()) == opened, "quick open selects the matching class");

            JTextField treeFilter = edt(() -> find(editor.window.getContentPane(), JTextField.class,
                    item -> !SwingUtilities.isDescendingFrom(item, editor.fileTabbedPanel)));
            edt(() -> { treeFilter.setText("app.properties"); return null; });
            await(() -> cn.enaium.joe.gui.component.FileTree.classesRoot.getChildCount() == 0
                    && cn.enaium.joe.gui.component.FileTree.resourceRoot.getChildCount() == 1, "live tree filter without Enter");
            edt(() -> { editor.fileTree.openEntry("demo/Sample.class"); return null; });
            check(edt(() -> editor.fileTree.getSelectionPath()) != null, "navigation reveals target under filter");
            resourceOperations(compiledBytes);

            Path cancelled = evidence.resolve("cancelled.jar");
            CompletableFuture<Boolean> cancelledExport = exportThroughChooser(cancelled, false);
            check(!cancelledExport.get(30, TimeUnit.SECONDS), "cancel export reports no write");
            check(!Files.exists(cancelled) && edt(() -> editor.hasArchiveChanges()), "cancel export keeps dirty archive");
            Path output = evidence.resolve("edited.jar");
            check(exportThroughChooser(output, true).get(30, TimeUnit.SECONDS), "archive export succeeds");
            check(!edt(() -> editor.hasArchiveChanges()), "successful export establishes clean baseline");
            check(Arrays.equals(inputBefore, Files.readAllBytes(input)), "default export preserves input archive");
            try (ZipFile zip = new ZipFile(output.toFile())) {
                check(value(zip.getInputStream(zip.getEntry("demo/Sample.class")).readAllBytes()) == 9, "exported class contains applied source");
                check(Arrays.equals("answer=9\n".getBytes(StandardCharsets.UTF_8), zip.getInputStream(zip.getEntry("config/app.properties")).readAllBytes()), "exported resource contains applied text");
            }

            edt(() -> { editor.fileTree.openEntry("config/app.properties"); return null; });
            RSyntaxTextArea newResource = edt(() -> find(editor.fileTabbedPanel.getSelectedComponent(), RSyntaxTextArea.class, item -> true));
            edt(() -> { newResource.setText("answer=10\n"); return null; });
            SwingUtilities.invokeLater(() -> {
                editor.window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                editor.window.dispatchEvent(new WindowEvent(editor.window, WindowEvent.WINDOW_CLOSING));
            });
            chooseOption(2);
            check(edt(() -> editor.window.isDisplayable() && newResource.getText().equals("answer=10\n")), "attached-style close cancel keeps window and draft");
            screenshot("protected-editor.png");
            System.out.println("DESKTOP_SMOKE_OK: load, captured source compilation, newer draft preservation, Java 8 target, Ctrl+P/Enter navigation, live filtering, Ctrl+W draft protection, resource import/replace/delete, exact entry export, archive export/cancellation, attached-style close cancellation.");
        } finally {
            edt(() -> {
                if (editor != null) editor.shutdown();
                for (Window window : Window.getWindows()) if (window.isDisplayable()) window.dispose();
                return null;
            });
        }
    }

    private static CompletableFuture<Boolean> exportThroughChooser(Path destination, boolean approve) throws Exception {
        AtomicReference<CompletableFuture<Boolean>> result = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> result.set(editor.exportArchive()));
        JFileChooser chooser = awaitValue(() -> {
            for (Window window : Window.getWindows()) if (window.isShowing()) {
                JFileChooser found = find(window, JFileChooser.class, item -> true);
                if (found != null) return found;
            }
            return null;
        }, "export chooser");
        edt(() -> { chooser.setSelectedFile(destination.toFile()); if (approve) chooser.approveSelection(); else chooser.cancelSelection(); return null; });
        await(() -> result.get() != null, "export future");
        return result.get();
    }

    private static void resourceOperations(byte[] compiledBytes) throws Exception {
        Path incoming = evidence.resolve("incoming.bin");
        byte[] first = { 0, -1, 42, 7, 5 };
        byte[] replacement = { 10, 20, -1, 30 };
        Files.write(incoming, first);
        SwingUtilities.invokeLater(() -> {
            JMenuItem add = null;
            for (int index = 0; index < editor.window.getJMenuBar().getMenuCount(); index++) {
                JMenu menu = editor.window.getJMenuBar().getMenu(index);
                for (Component child : menu.getMenuComponents()) if (child instanceof JMenuItem item
                        && item.getText().equals(LangUtil.i18n("entry.add"))) add = item;
            }
            if (add == null) throw new AssertionError("Add resource menu missing");
            add.doClick();
        });
        chooseFile(incoming);
        JOptionPane input = awaitPane();
        edt(() -> {
            JTextField field = find(input, JTextField.class, item -> item.isEditable());
            if (field == null) throw new AssertionError("Resource path input missing");
            field.setText("new/data.bin");
            SwingUtilities.getRootPane(input).getDefaultButton().doClick();
            return null;
        });
        await(() -> Arrays.equals(first, editor.getJar().getResource("new/data.bin")), "resource import");
        Path replacing = evidence.resolve("replacement.bin");
        Files.write(replacing, replacement);
        clickResource("new/data.bin", "entry.replace");
        chooseFile(replacing);
        confirmDefault();
        await(() -> Arrays.equals(replacement, editor.getJar().getResource("new/data.bin")), "resource replacement");
        Path exported = evidence.resolve("entry.bin");
        clickResource("new/data.bin", "entry.export");
        chooseFile(exported);
        await(() -> Files.isRegularFile(exported), "single resource export");
        check(Arrays.equals(replacement, Files.readAllBytes(exported)), "single resource export is exact");
        screenshot("resource-actions.png");

        Path classOutput = evidence.resolve("Sample.class");
        SwingUtilities.invokeLater(() -> {
            JPopupMenu popup = new JPopupMenu();
            EntryActions.populatePopup(popup, "demo/Sample.class", true);
            for (Component child : popup.getComponents()) if (child instanceof JMenuItem item
                    && item.getText().equals(LangUtil.i18n("entry.export"))) item.doClick();
        });
        chooseFile(classOutput);
        await(() -> Files.isRegularFile(classOutput), "single class export");
        check(Arrays.equals(compiledBytes, Files.readAllBytes(classOutput)), "single class export contains current bytecode");
        clickResource("new/data.bin", "entry.delete");
        confirmDefault();
        await(() -> editor.getJar().getResource("new/data.bin") == null, "resource deletion");
        check(edt(() -> editor.fileTabbedPanel.getTabCount()) == 2, "deleting resource preserves class tabs");
    }

    private static void clickResource(String path, String key) throws Exception {
        edt(() -> { editor.fileTree.openEntry(path); return null; });
        SwingUtilities.invokeLater(() -> {
            AbstractButton button = find(editor.fileTabbedPanel.getSelectedComponent(), AbstractButton.class,
                    item -> item.getText().equals(LangUtil.i18n(key)));
            if (button == null) throw new AssertionError("Resource action missing: " + key);
            button.doClick();
        });
    }

    private static void chooseFile(Path path) throws Exception {
        JFileChooser chooser = awaitValue(() -> {
            for (Window window : Window.getWindows()) if (window.isShowing()) {
                JFileChooser found = find(window, JFileChooser.class, item -> true);
                if (found != null) return found;
            }
            return null;
        }, "entry chooser");
        edt(() -> { chooser.setSelectedFile(path.toFile()); chooser.approveSelection(); return null; });
    }

    private static JOptionPane awaitPane() throws Exception {
        return awaitValue(() -> {
            for (Window window : Window.getWindows()) if (window.isShowing()) {
                JOptionPane found = find(window, JOptionPane.class, item -> true);
                if (found != null) return found;
            }
            return null;
        }, "entry confirmation");
    }

    private static void confirmDefault() throws Exception {
        JOptionPane pane = awaitPane();
        edt(() -> { SwingUtilities.getRootPane(pane).getDefaultButton().doClick(); return null; });
    }

    private static void fireCloseTab() throws Exception {
        Component target = edt(() -> find(editor.fileTabbedPanel.getSelectedComponent(), RSyntaxTextArea.class, item -> item.isShowing()));
        pressKey(target, KeyEvent.VK_W, true);
    }

    private static void chooseOption(int index) throws Exception {
        JOptionPane pane = awaitValue(() -> {
            for (Window window : Window.getWindows()) if (window.isShowing()) {
                JOptionPane found = find(window, JOptionPane.class, item -> true);
                if (found != null) return found;
            }
            return null;
        }, "confirmation dialog");
        edt(() -> {
            Object[] options = pane.getOptions();
            if (options == null || index >= options.length) throw new AssertionError("Unexpected confirmation options");
            AbstractButton button = find(SwingUtilities.getWindowAncestor(pane), AbstractButton.class,
                    item -> item.getText().equals(String.valueOf(options[index])));
            if (button == null) throw new AssertionError("Confirmation button missing");
            button.doClick();
            return null;
        });
    }

    private static void screenshot(String filename) throws Exception {
        Robot robot = new Robot();
        robot.waitForIdle();
        // Native dialog-close animations may outlive the Swing window's visibility.
        robot.delay(350);
        Rectangle bounds = edt(() -> editor.window.getBounds());
        ImageIO.write(robot.createScreenCapture(bounds), "png", evidence.resolve(filename).toFile());
    }

    private static void pressKey(Component target, int key, boolean control) throws Exception {
        check(target != null, "keyboard target exists");
        edt(() -> {
            SwingUtilities.getWindowAncestor(target).toFront();
            target.requestFocusInWindow();
            return null;
        });
        await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == target, "keyboard focus");
        Robot robot = new Robot();
        robot.setAutoDelay(40);
        if (control) robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(key);
        robot.keyRelease(key);
        if (control) robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.waitForIdle();
    }

    private static void await(Callable<Boolean> condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out: " + description);
    }

    private static <T> T awaitValue(Callable<T> probe, String description) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        await(() -> { T value = probe.call(); result.set(value); return value != null; }, description);
        return result.get();
    }

    private static <T> T edt(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return work.call();
        FutureTask<T> task = new FutureTask<>(work);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static <T extends Component> T find(Component component, Class<T> type, Predicate<T> predicate) {
        if (component == null) return null;
        if (type.isInstance(component) && predicate.test(type.cast(component))) return type.cast(component);
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            T found = find(child, type, predicate);
            if (found != null) return found;
        }
        return null;
    }

    private static <T extends Window> T visibleWindow(Class<T> type) {
        for (Window window : Window.getWindows()) if (type.isInstance(window) && window.isShowing()) return type.cast(window);
        return null;
    }

    private static int value(byte[] bytes) throws Exception {
        String name = new org.objectweb.asm.ClassReader(bytes).getClassName().replace('/', '.');
        Class<?> loaded = new ClassLoader() { Class<?> load() { return defineClass(name, bytes, 0, bytes.length); } }.load();
        return (int) loaded.getMethod("value").invoke(null);
    }

    private static byte[] classBytes(String name, int result) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode(); method.visitIntInsn(Opcodes.BIPUSH, result); method.visitInsn(Opcodes.IRETURN); method.visitMaxs(1, 0); method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
