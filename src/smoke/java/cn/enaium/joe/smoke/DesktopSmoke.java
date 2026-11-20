package cn.enaium.joe.smoke;

import cn.enaium.joe.JavaOctetEditor;
import cn.enaium.joe.dialog.QuickOpenDialog;
import cn.enaium.joe.gui.EntryActions;
import cn.enaium.joe.gui.panel.file.tabbed.tab.classes.DecompileTabPanel;
import cn.enaium.joe.gui.panel.file.tabbed.tab.classes.ASMifierTablePanel;
import cn.enaium.joe.gui.editor.EditorDraft;
import cn.enaium.joe.gui.panel.CodeAreaPanel;
import cn.enaium.joe.gui.panel.menu.file.LoadRecentMenu;
import cn.enaium.joe.service.DecompileService;
import cn.enaium.joe.util.compiler.CompilerDiagnostic;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
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
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
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
                LoadRecentMenu recent = openRecentMenu();
                check(!menuItem(recent, "menu.file.recent.empty").isEnabled(), "empty recent files menu gives guidance");
                dismissMenus();
                editor.requestOpenArchive(input.toFile());
                return null;
            });
            await(() -> editor.getJar() != null, "initial archive load");
            check(edt(() -> editor.getArchiveSource().equals(input.toFile())), "source path accepted after load");
            check(!edt(() -> editor.hasArchiveChanges()), "loaded archive starts clean");
            recentFilesExperience(input, inputBefore);

            edt(() -> { editor.fileTree.openEntry("demo/Sample.class"); return null; });
            Component classTab = edt(() -> editor.fileTabbedPanel.getSelectedComponent());
            screenshot("bytecode-on-open.png");
            JTabbedPane classViews = edt(() -> find(classTab, JTabbedPane.class, item -> item.getTabCount() == 4));
            edt(() -> {
                classViews.setSelectedIndex(1);
                JButton cancel = find(classTab, JButton.class, item -> item.getText().equals(LangUtil.i18n("source.cancel")));
                check(cancel != null && cancel.isEnabled(), "source decompilation can be cancelled locally");
                cancel.doClick(0);
                return null;
            });
            DecompileTabPanel sourcePanel = edt(() -> find(classTab, DecompileTabPanel.class, item -> true));
            RSyntaxTextArea source = edt(() -> find(sourcePanel, RSyntaxTextArea.class, item -> true));
            await(() -> find(sourcePanel, JLabel.class, item -> LangUtil.i18n("source.cancelled").equals(item.getText())) != null,
                    "cancelled source has a recovery action");
            JButton retry = edt(() -> find(sourcePanel, JButton.class, item -> item.getText().equals(LangUtil.i18n("source.retry"))));
            check(edt(retry::isEnabled), "retry is enabled after cancellation");
            screenshot("source-cancelled.png");
            edt(() -> { retry.doClick(0); return null; });
            await(() -> source.isEditable() && source.getText().contains("class Sample") && retry.isEnabled(), "source retry without reopening tab");
            JComboBox<?> backend = edt(() -> find(sourcePanel, JComboBox.class,
                    item -> item.getSelectedItem() instanceof DecompileService.Service));
            edt(() -> { backend.setSelectedItem(DecompileService.Service.CFR); return null; });
            await(() -> retry.isEnabled() && source.isEditable() && source.getText().contains("class Sample"), "source backend switch");
            pressKey(source, KeyEvent.VK_1, true);
            check(edt(() -> classViews.getSelectedIndex() == 0), "Ctrl+1 switches to the bytecode view");
            edt(() -> { classViews.setSelectedIndex(1); return null; });
            check(edt(() -> find(classTab, JTextField.class, item -> !item.isEditable()
                    && item.getText().equals("demo/Sample.class"))) != null, "class header exposes full path");
            String invalidSource = "package demo;\npublic class Sample {\n    public static int value() {\n        return ;\n    }\n}\n";
            compilationFailureExperience(sourcePanel, sourcePanel, invalidSource, invalidSource.indexOf("return"), "source-diagnostics.png");
            String validSource = "package demo; public class Sample { public static int value() { return 9; } }";
            CompletableFuture<Boolean> capturedCompile = edt(() -> {
                source.setText("package demo; public class Sample { public static int value() { return 7; } }");
                CompletableFuture<Boolean> pending = sourcePanel.saveDraft();
                source.setText(validSource);
                return pending;
            });
            check(edt(() -> !backend.isEnabled()), "backend changes cannot replace a newer source draft");
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
            String sourceDraft = validSource + "\n// keep this draft while switching views";
            edt(() -> {
                source.setText(sourceDraft);
                source.setCaretPosition(20);
                source.moveCaretPosition(40);
                classViews.setSelectedIndex(0);
                classViews.setSelectedIndex(3);
                classViews.setSelectedIndex(1);
                return null;
            });
            check(edt(() -> source.isShowing() && source.getText().equals(sourceDraft)
                    && source.getCaret().getMark() == 20 && source.getCaret().getDot() == 40), "source draft and selection survive view changes");
            edt(() -> { source.setText(validSource); classViews.setSelectedIndex(2); return null; });
            ASMifierTablePanel visitorPanel = edt(() -> find(classTab, ASMifierTablePanel.class, item -> true));
            RSyntaxTextArea visitor = edt(() -> find(visitorPanel, RSyntaxTextArea.class, item -> true));
            await(visitor::isEditable, "visitor editor initialization");
            String visitorBaseline = edt(visitor::getText);
            String invalidVisitor = "ClassWriter classWriter = new ClassWriter(0);\nmissingSymbol();\n";
            compilationFailureExperience(visitorPanel, visitorPanel, invalidVisitor,
                    invalidVisitor.indexOf("missingSymbol"), "visitor-diagnostics.png");
            edt(() -> { visitor.setText(visitorBaseline); return null; });
            String visitorDraft = visitorBaseline + "\n// keep this visitor draft";
            edt(() -> {
                visitor.setText(visitorDraft);
                classViews.setSelectedIndex(1);
                classViews.setSelectedIndex(2);
                return null;
            });
            check(edt(() -> visitor.isShowing() && visitor.getText().equals(visitorDraft)
                    && visitorPanel.hasDraft()), "visitor draft survives deferred view refresh");
            edt(() -> { visitor.setText(visitorBaseline); classViews.setSelectedIndex(1); return null; });
            screenshot("source-after-compile.png");

            edt(() -> { editor.fileTree.openEntry("config/app.properties"); return null; });
            Component resourceTab = edt(() -> editor.fileTabbedPanel.getSelectedComponent());
            int opened = edt(() -> editor.fileTabbedPanel.getTabCount());
            edt(() -> { editor.fileTree.openEntry("config/app.properties"); return null; });
            check(edt(() -> editor.fileTabbedPanel.getTabCount()) == opened, "resource navigation reuses tab");
            RSyntaxTextArea resource = edt(() -> find(resourceTab, RSyntaxTextArea.class, item -> true));
            searchExperience(resourceTab, resource);
            edt(() -> { resource.setText("answer=9\n"); return null; });
            check(edt(() -> new String(editor.getJar().getResource("config/app.properties"), StandardCharsets.UTF_8).equals("answer=1\n")), "draft does not mutate resource before apply");
            check(edt(() -> cn.enaium.joe.gui.editor.DraftSupport.hasDrafts(resourceTab)), "edited resource has an unapplied draft");
            fireCloseTab();
            chooseOption(2);
            check(edt(() -> editor.fileTabbedPanel.indexOfComponent(resourceTab)) >= 0, "cancel close retains tab");
            check(edt(() -> resource.getText().equals("answer=9\n")), "cancel close retains text");
            screenshot("resource-draft.png");
            fireCloseTab();
            chooseOption(0);
            await(() -> editor.fileTabbedPanel.indexOfComponent(resourceTab) < 0, "apply-and-close resource");
            check(edt(() -> Arrays.equals(editor.getJar().getResource("config/app.properties"), "answer=9\n".getBytes(StandardCharsets.UTF_8))), "apply-and-close commits resource");

            edt(() -> { source.setText(sourceDraft); return null; });
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
            check(edt(() -> editor.fileTabbedPanel.getTitleAt(0).contains("demo")
                    && editor.fileTabbedPanel.getTitleAt(1).contains("other")), "same-name class tabs display their packages");
            int closingIndex = edt(() -> editor.fileTabbedPanel.getSelectedIndex());
            edt(() -> { middleClickTab(closingIndex); return null; });
            await(() -> editor.fileTabbedPanel.getTabCount() == 1, "middle-click closes colliding clean class tab");
            check(edt(() -> editor.fileTabbedPanel.getTitleAt(0).equals("Sample")), "closing collision restores compact title");
            check(edt(() -> cn.enaium.joe.gui.panel.file.tabbed.TabDraftIndicator.isMarked(
                    editor.fileTabbedPanel, 0)), "dirty class keeps a fixed-width draft indicator");
            edt(() -> { editor.fileTabbedPanel.reopenClosedTab(); return null; });
            await(() -> editor.fileTabbedPanel.getTabCount() == 2
                    && editor.fileTabbedPanel.getTitleAt(1).contains("other"), "reopen closed class tab");
            edt(() -> { editor.fileTree.openEntry("other/Sample.class"); return null; });
            check(edt(() -> editor.fileTabbedPanel.getTitleAt(0).contains("demo")), "reopening collision preserves the original draft title");
            check(edt(() -> cn.enaium.joe.gui.panel.file.tabbed.TabDraftIndicator.isMarked(
                    editor.fileTabbedPanel, 0)), "reopening collision preserves the draft indicator");
            screenshot("same-name-tabs.png");

            JTextField treeFilter = edt(() -> find(editor.window.getContentPane(), JTextField.class,
                    item -> !SwingUtilities.isDescendingFrom(item, editor.fileTabbedPanel)));
            edt(() -> { treeFilter.setText("app.properties"); return null; });
            await(() -> cn.enaium.joe.gui.component.FileTree.classesRoot.getChildCount() == 0
                    && cn.enaium.joe.gui.component.FileTree.resourceRoot.getChildCount() == 1, "live tree filter without Enter");
            edt(() -> { editor.fileTree.openEntry("demo/Sample.class"); return null; });
            check(edt(() -> editor.fileTree.getSelectionPath()) != null, "navigation reveals target under filter");
            check(edt(() -> source.isShowing() && sourcePanel.hasDraft() && source.getText().equals(sourceDraft)),
                    "source draft survives outer tab navigation and filtering");
            edt(() -> { source.setText(validSource); return null; });

            edt(() -> { classViews.setSelectedIndex(3); return null; });
            cn.enaium.joe.gui.panel.file.tabbed.tab.classes.ClassInfoTabPanel infoPanel =
                    edt(() -> find(classTab, cn.enaium.joe.gui.panel.file.tabbed.tab.classes.ClassInfoTabPanel.class, item -> true));
            JTextField sourceFileName = edt(() -> find(infoPanel, JTextField.class, item -> "Sample.java".equals(item.getText())));
            check(sourceFileName != null, "class metadata exposes the source file");
            edt(() -> {
                sourceFileName.setText("Smoke.java");
                classViews.setSelectedIndex(1);
                classViews.setSelectedIndex(3);
                return null;
            });
            check(edt(() -> infoPanel.hasDraft() && sourceFileName.getText().equals("Smoke.java")),
                    "class metadata draft survives view switching");
            check(edt(() -> infoPanel.saveDraft()).get(20, TimeUnit.SECONDS), "class metadata draft applies");
            check(edt(() -> "Smoke.java".equals(editor.getJar().getClassNode("demo/Sample").getNodeInternal().sourceFile)),
                    "applied class metadata is visible in the model");
            check(edt(() -> editor.hasArchiveChanges()), "class metadata marks the archive modified");
            await(() -> !cn.enaium.joe.gui.panel.file.tabbed.TabDraftIndicator.isMarked(
                    editor.fileTabbedPanel, 0), "metadata save clears the draft indicator");
            screenshot("class-info-draft.png");
            edt(() -> { classViews.setSelectedIndex(1); return null; });
            await(() -> !sourcePanel.hasDraft() && source.isEditable(), "source baseline after metadata refresh");
            byte[] currentClassBytes = edt(() -> editor.getJar().getClassNode("demo/Sample").getClassBytes().clone());
            resourceOperations(currentClassBytes);

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

            Jar reloaded = ArchiveIO.read(input);
            reloaded.putResource("config/reloaded.txt", "reloaded\n".getBytes(StandardCharsets.UTF_8));
            ArchiveIO.write(reloaded, input);
            edt(() -> { editor.reloadArchive(); return null; });
            await(() -> editor.getJar() != null
                    && editor.getJar().getResource("config/reloaded.txt") != null, "reload archive from disk");
            check(edt(() -> editor.getArchiveSource().equals(input.toFile())), "reload retains the source path");
            check(!edt(() -> editor.hasArchiveChanges()), "reload establishes a clean baseline");
            screenshot("reloaded-archive.png");

            Path broken = evidence.resolve("broken.jar");
            Files.writeString(broken, "not an archive");
            edt(() -> { editor.requestOpenArchive(broken.toFile()); return null; });
            JDialog failure = awaitValue(DesktopSmoke::errorDialog, "archive load failure dialog");
            check(edt(failure::getWidth) <= 1000 && edt(failure::getHeight) <= 800,
                    "load failure details stay bounded");
            check(edt(() -> find(failure, JTextArea.class, item -> item.getText().contains("broken.jar"))) != null,
                    "load failure identifies the affected file");
            screenshotWindow(failure, "load-error.png");
            edt(() -> { failure.dispose(); return null; });
            check(edt(() -> editor.getJar() != null
                    && editor.getJar().getResource("config/reloaded.txt") != null), "failed load preserves the current archive");

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
            System.out.println("DESKTOP_SMOKE_OK: class metadata drafts and validation publication, load-failure error details, middle-click close/reopen, reload from disk, source/visitor diagnostic navigation and stale guards, editor search feedback and shortcuts, decompiler cancel/retry/backend switch, class path headers and collision titles, ordered recent files and missing-entry cleanup, captured compilation, newer drafts, Java 8 target, navigation/filtering, guarded close, resource actions and export.");
        } finally {
            edt(() -> {
                if (editor != null) editor.shutdown();
                for (Window window : Window.getWindows()) if (window.isDisplayable()) window.dispose();
                return null;
            });
        }
    }

    private static LoadRecentMenu openRecentMenu() {
        for (int index = 0; index < editor.window.getJMenuBar().getMenuCount(); index++) {
            JMenu menu = editor.window.getJMenuBar().getMenu(index);
            for (Component child : menu.getMenuComponents()) if (child instanceof LoadRecentMenu recent) {
                menu.doClick(0);
                recent.doClick(0);
                return recent;
            }
        }
        throw new AssertionError("Recent files menu missing");
    }

    private static JMenuItem menuItem(JMenu menu, String key) {
        for (Component child : menu.getMenuComponents()) if (child instanceof JMenuItem item
                && item.getText().equals(LangUtil.i18n(key))) return item;
        throw new AssertionError("Menu action missing: " + key);
    }

    private static void dismissMenus() {
        MenuSelectionManager.defaultManager().clearSelectedPath();
        for (int index = 0; index < editor.window.getJMenuBar().getMenuCount(); index++) {
            JMenu menu = editor.window.getJMenuBar().getMenu(index);
            for (Component child : menu.getMenuComponents()) if (child instanceof JMenu submenu) submenu.setPopupMenuVisible(false);
            menu.setPopupMenuVisible(false);
        }
    }

    private static void recentFilesExperience(Path input, byte[] bytes) throws Exception {
        Path other = evidence.resolve("other.jar");
        Path missing = evidence.resolve("missing.jar");
        Files.write(other, bytes);
        Files.write(missing, bytes);
        edt(() -> { editor.requestOpenArchive(other.toFile()); return null; });
        await(() -> other.toFile().equals(editor.getArchiveSource()), "second recent archive");
        edt(() -> { editor.requestOpenArchive(missing.toFile()); return null; });
        await(() -> missing.toFile().equals(editor.getArchiveSource()), "missing-entry fixture load");
        Files.delete(missing);
        edt(() -> { editor.requestOpenArchive(input.toFile()); return null; });
        await(() -> input.toFile().equals(editor.getArchiveSource()), "reopen most recent archive");
        check(edt(() -> editor.CONFIG.getByClass(ApplicationConfig.class).loadRecent.getValue())
                .equals(List.of(input.toString(), missing.toString(), other.toString())), "recent files are unique and newest first");
        edt(() -> {
            LoadRecentMenu menu = openRecentMenu();
            check(input.toString().equals(menu.getItem(0).getToolTipText()), "recent menu exposes latest full path first");
            menuItem(menu, "menu.file.recent.removeMissing").doClick(0);
            dismissMenus();
            return null;
        });
        await(() -> editor.CONFIG.getByClass(ApplicationConfig.class).loadRecent.getValue()
                .equals(List.of(input.toString(), other.toString())), "recent cleanup removes missing shortcuts");
        check(Files.exists(input) && Files.exists(other), "recent cleanup preserves files");
    }

    private static JList<?> diagnosticList(Component root) {
        return find(root, JList.class, list -> list.getModel().getSize() > 0
                && list.getModel().getElementAt(0) instanceof CompilerDiagnostic);
    }

    private static void compilationFailureExperience(Component panel, EditorDraft draft, String invalid,
                                                     int expectedPosition, String filename) throws Exception {
        RSyntaxTextArea text = edt(() -> find(panel, RSyntaxTextArea.class, item -> true));
        byte[] before = edt(() -> editor.getJar().getClassNode("demo/Sample").getClassBytes().clone());
        CompletableFuture<Boolean> applied = edt(() -> { text.setText(invalid); return draft.saveDraft(); });
        check(!applied.get(30, TimeUnit.SECONDS), "invalid source is not applied");
        await(() -> diagnosticList(panel) != null && diagnosticList(panel).isShowing(), "inline compilation diagnostics");
        JList<?> list = edt(() -> diagnosticList(panel));
        CompilerDiagnostic first = edt(() -> (CompilerDiagnostic) list.getModel().getElementAt(0));
        check(first.kind() == javax.tools.Diagnostic.Kind.ERROR && first.position() == expectedPosition, "diagnostic addresses displayed source");
        check(edt(() -> {
            for (Window window : Window.getWindows()) if (window instanceof JDialog dialog && dialog.isShowing()
                    && dialog.getTitle().equals(LangUtil.i18n("error"))) return false;
            return true;
        }), "expected compilation failure keeps editing in the source window");
        edt(() -> { list.setSelectedIndex(0); return null; });
        pressKey(list, KeyEvent.VK_ENTER, false);
        check(edt(text::getCaretPosition) == expectedPosition, "diagnostic opens exact source location");
        screenshot(filename);
        edt(() -> { text.append("\n// newer text"); return null; });
        check(!edt(list::isEnabled), "editing invalidates old diagnostic navigation");
        check(edt(draft::hasDraft), "failed compile preserves the editor draft");
        check(Arrays.equals(before, edt(() -> editor.getJar().getClassNode("demo/Sample").getClassBytes().clone())),
                "failed compile preserves archive bytecode");
    }

    private static void searchExperience(Component panel, RSyntaxTextArea text) throws Exception {
        String baseline = edt(text::getText);
        edt(() -> { text.setText("alpha=1\nalpha=2\n"); text.setCaretPosition(text.getDocument().getLength()); return null; });
        pressKey(text, KeyEvent.VK_F, true);
        CodeAreaPanel code = edt(() -> find(panel, CodeAreaPanel.class, item -> true));
        JToolBar toolbar = edt(() -> find(code, JToolBar.class, item -> true));
        JTextField query = edt(() -> find(toolbar, JTextField.class, item -> true));
        edt(() -> { query.setText("alpha"); return null; });
        pressKey(query, KeyEvent.VK_ENTER, false);
        check(edt(text::getSelectionStart) == 0 && "alpha".equals(edt(text::getSelectedText)), "search wraps to first match");
        pressKey(text, KeyEvent.VK_F3, false);
        check(edt(text::getSelectionStart) == 8, "F3 finds the next occurrence");
        int selected = edt(text::getSelectionStart);
        edt(() -> {
            find(toolbar, JCheckBox.class, item -> item.getText().equals(LangUtil.i18n("editor.search.regex"))).setSelected(true);
            query.setText("[");
            return null;
        });
        pressKey(query, KeyEvent.VK_ENTER, false);
        check(edt(() -> find(code, JLabel.class, item -> item.isShowing() && item.getText() != null
                && item.getText().startsWith(LangUtil.i18n("editor.search.invalidRegex", "")))) != null, "invalid regex explains the problem inline");
        check(edt(text::getSelectionStart) == selected, "invalid regex preserves editor selection");
        screenshot("editor-search.png");
        edt(() -> {
            find(toolbar, JCheckBox.class, item -> item.getText().equals(LangUtil.i18n("editor.search.regex"))).setSelected(false);
            query.setText("missing key");
            return null;
        });
        pressKey(query, KeyEvent.VK_ENTER, false);
        check(edt(() -> find(code, JLabel.class, item -> LangUtil.i18n("editor.search.noMatches").equals(item.getText()))) != null,
                "no-match feedback is visible");
        pressKey(query, KeyEvent.VK_ESCAPE, false);
        check(!edt(toolbar::isVisible), "Escape closes search from its input");
        edt(() -> { text.setText(baseline); return null; });
    }

    private static JDialog errorDialog() {
        for (Window window : Window.getWindows()) if (window instanceof JDialog dialog && dialog.isShowing()
                && LangUtil.i18n("error").equals(dialog.getTitle())) return dialog;
        return null;
    }

    private static void screenshotWindow(Window window, String filename) throws Exception {
        edt(() -> {
            BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                window.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(image, "png", evidence.resolve(filename).toFile());
            return null;
        });
    }

    private static void middleClickTab(int index) {
        Rectangle bounds = editor.fileTabbedPanel.getBoundsAt(index);
        if (bounds == null) throw new AssertionError("Tab bounds missing");
        MouseEvent event = new MouseEvent(editor.fileTabbedPanel, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON2_DOWN_MASK,
                bounds.x + Math.max(1, bounds.width / 2), bounds.y + Math.max(1, bounds.height / 2),
                1, false, MouseEvent.BUTTON2);
        editor.fileTabbedPanel.dispatchEvent(event);
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
        edt(() -> {
            BufferedImage image = new BufferedImage(editor.window.getWidth(), editor.window.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                editor.window.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(image, "png", evidence.resolve(filename).toFile());
            return null;
        });
    }

    private static void pressKey(Component target, int key, boolean control) throws Exception {
        check(target != null, "keyboard target exists");
        FutureTask<Void> dispatch = new FutureTask<>(() -> {
            check(target.isShowing(), "keyboard target is visible");
            int modifiers = control ? InputEvent.CTRL_DOWN_MASK : 0;
            KeyEvent event = new KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED);
            check(SwingUtilities.processKeyBindings(event), "keyboard binding handles " + KeyEvent.getKeyText(key));
            return null;
        });
        SwingUtilities.invokeLater(dispatch);
        edt(() -> {
            if (dispatch.isDone()) dispatch.get();
            return null;
        });
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
        writer.visitSource("Sample.java", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode(); method.visitIntInsn(Opcodes.BIPUSH, result); method.visitInsn(Opcodes.IRETURN); method.visitMaxs(1, 0); method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
