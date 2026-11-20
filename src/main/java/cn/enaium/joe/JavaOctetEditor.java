/*
 * Copyright 2022 Enaium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.enaium.joe;

import cn.enaium.joe.util.config.extend.CFRConfig;
import cn.enaium.joe.util.config.extend.ProcyonConfig;
import cn.enaium.joe.util.config.extend.VineFlowerConfig;
import cn.enaium.joe.util.config.ConfigManager;
import cn.enaium.joe.util.config.extend.ApplicationConfig;
import cn.enaium.joe.util.event.EventManager;
import cn.enaium.joe.gui.panel.BorderPanel;
import cn.enaium.joe.gui.panel.BottomPanel;
import cn.enaium.joe.gui.panel.file.tree.CenterPanel;
import cn.enaium.joe.gui.panel.file.tabbed.FileTabbedPanel;
import cn.enaium.joe.gui.panel.file.tabbed.tab.classes.ClassTabPanel;
import cn.enaium.joe.gui.component.FileTree;
import cn.enaium.joe.gui.panel.menu.*;
import cn.enaium.joe.gui.editor.ArchiveDocumentState;
import cn.enaium.joe.gui.editor.DraftSupport;
import cn.enaium.joe.gui.panel.file.tabbed.tab.resources.FileTablePane;
import cn.enaium.joe.dialog.QuickOpenDialog;
import cn.enaium.joe.jar.ArchiveSnapshot;
import cn.enaium.joe.util.task.tasks.InputJarTask;
import cn.enaium.joe.util.task.tasks.OutputJarTask;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.task.TaskManager;
import cn.enaium.joe.util.*;
import cn.enaium.joe.util.reflection.ReflectionHelper;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;

import cn.enaium.joe.gui.WindowGeometry;
import javax.swing.*;
import java.awt.event.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * @author Enaium
 */
public class JavaOctetEditor {
    private static JavaOctetEditor INSTANCE;

    public static final String TITLE = "JavaOctetEditor";

    public static final String VERSION = "1.5.0";

    public JFrame window;

    private volatile Jar jar;

    private CenterPanel workspace;
    private boolean closed;
    private final cn.enaium.joe.util.task.LoadRequestState loadRequests = new cn.enaium.joe.util.task.LoadRequestState();
    private final ArchiveDocumentState document = new ArchiveDocumentState();
    private File archiveSource;
    private File outputDestination;
    private final AtomicLong draftRevision = new AtomicLong();
    private volatile boolean draftsCache;
    private volatile long draftsCacheRevision = Long.MIN_VALUE;
    private boolean draftPresentationPending;
    private boolean closePending;

    public File getArchiveSource() {
        return archiveSource;
    }

    public boolean hasArchiveChanges() {
        return document.isDirty();
    }

    public boolean hasUnappliedDrafts() {
        long revision = draftRevision.get();
        if (draftsCacheRevision != revision) {
            draftsCache = fileTabbedPanel != null && DraftSupport.hasDrafts(fileTabbedPanel);
            draftsCacheRevision = revision;
        }
        return draftsCache;
    }

    public void markArchiveModified() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::markArchiveModified);
            return;
        }
        if (closed) return;
        document.changed();
        refreshDocumentPresentation();
    }

    public void draftsChanged() {
        draftRevision.incrementAndGet();
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::scheduleDraftPresentation);
            return;
        }
        scheduleDraftPresentation();
    }

    private void scheduleDraftPresentation() {
        if (draftPresentationPending) return;
        draftPresentationPending = true;
        SwingUtilities.invokeLater(() -> {
            draftPresentationPending = false;
            if (fileTabbedPanel != null) fileTabbedPanel.refreshDraftIndicators();
            refreshDocumentPresentation();
        });
    }

    private void refreshDocumentPresentation() {
        if (window != null) {
            String name = archiveSource == null ? LangUtil.i18n("document.untitled") : archiveSource.getName();
            boolean drafts = hasUnappliedDrafts();
            String state = drafts ? LangUtil.i18n("document.drafts") : "";
            if (document.isDirty()) state += (state.isEmpty() ? "" : ", ") + LangUtil.i18n("document.modified");
            window.setTitle((drafts || document.isDirty() ? "* " : "") + TITLE
                    + (jar == null ? "" : " — " + name) + (state.isEmpty() ? "" : " [" + state + "]"));
        }
        if (bottomPanel != null) bottomPanel.refreshStatus();
    }

    public void notifyStatus(String message) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> notifyStatus(message));
            return;
        }
        if (bottomPanel != null) bottomPanel.notifyStatus(message);
    }

    public DraftSupport.Consent confirmDiscardEntryDrafts(String path) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Draft confirmation must run on EDT");
        Component scope = findResourcePanel(fileTabbedPanel, path);
        return DraftSupport.confirmDiscard(scope == null ? new JPanel() : scope, window);
    }

    private Component findResourcePanel(Component component, String path) {
        if (component instanceof FileTablePane panel && panel.getArchive() == jar && panel.getEntryPath().equals(path)) return panel;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = findResourcePanel(child, path);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void requestOpenArchive(File file) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestOpenArchive(file));
            return;
        }
        if (file == null || closed) return;
        long request = beginArchiveLoad();
        authorizeReplacement(() -> isArchiveLoadCurrent(request)).thenAccept(authorization -> onEdt(() -> {
            if (authorization == null || !isArchiveLoadCurrent(request)) return;
            TASKS.submit(new InputJarTask(file, request, () -> authorization.isValid()));
        }));
    }

    /** Reloads the current archive through the normal guarded open flow. EDT only. */
    public void reloadArchive() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reloadArchive);
            return;
        }
        if (closed) return;
        if (archiveSource == null) {
            MessageUtil.warning(LangUtil.i18n("document.reloadUnavailable"));
            return;
        }
        requestOpenArchive(archiveSource);
    }

    private CompletableFuture<Authorization> authorizeReplacement(BooleanSupplier current) {
        CompletableFuture<Authorization> result = new CompletableFuture<>();
        DraftSupport.confirmClose(fileTabbedPanel, window).whenComplete((consent, failure) -> onEdt(() -> {
            if (failure != null || consent == null || !current.getAsBoolean() || !consent.isValid()) {
                result.complete(null);
                return;
            }
            document.refreshDirty();
            refreshDocumentPresentation();
            Authorization authorization = new Authorization(document.captureGuard(), draftRevision.get(), consent);
            if (!document.isDirty()) {
                result.complete(authorization);
                return;
            }
            Object[] options = {LangUtil.i18n("document.export"), LangUtil.i18n("document.discard"), LangUtil.i18n("document.cancel")};
            int answer = JOptionPane.showOptionDialog(window, LangUtil.i18n("document.unsavedArchive"),
                    LangUtil.i18n("warning"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (!current.getAsBoolean() || !authorization.isValid()) {
                notifyStatus(LangUtil.i18n("document.changedRetry"));
                result.complete(null);
            } else if (answer == 1) {
                result.complete(authorization);
            } else if (answer == 0) {
                exportCurrentArchive().whenComplete((success, exportFailure) -> onEdt(() -> {
                    boolean allowed = exportFailure == null && Boolean.TRUE.equals(success)
                            && current.getAsBoolean() && authorization.isValid() && !document.isDirty();
                    if (Boolean.TRUE.equals(success) && !allowed) notifyStatus(LangUtil.i18n("document.changedRetry"));
                    result.complete(allowed ? authorization : null);
                }));
            } else result.complete(null);
        }));
        return result;
    }

    private final class Authorization {
        private final ArchiveDocumentState.Guard model;
        private final long drafts;
        private final DraftSupport.Consent consent;

        private Authorization(ArchiveDocumentState.Guard model, long drafts, DraftSupport.Consent consent) {
            this.model = model;
            this.drafts = drafts;
            this.consent = consent;
        }

        private boolean isValid() {
            return !closed && drafts == draftRevision.get() && consent.isValid() && document.isCurrent(model);
        }
    }

    public void acceptArchiveLoad(File file, Jar loaded) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Archive publication must run on EDT");
        setJar(loaded);
        archiveSource = file.getAbsoluteFile();
        outputDestination = null;
        CONFIG.getByClass(ApplicationConfig.class).loadRecent.remember(archiveSource.toPath());
        refreshDocumentPresentation();
        notifyStatus(LangUtil.i18n("document.opened", archiveSource.getAbsolutePath()));
    }

    public CompletableFuture<Boolean> exportArchive() {
        if (!SwingUtilities.isEventDispatchThread()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            onEdt(() -> exportArchive().whenComplete((success, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(success);
            }));
            return result;
        }
        if (!hasUnappliedDrafts()) return exportCurrentArchive();
        Object[] options = {LangUtil.i18n("document.applyDrafts"), LangUtil.i18n("document.exportApplied"), LangUtil.i18n("document.cancel")};
        Jar original = jar;
        int answer = JOptionPane.showOptionDialog(window, LangUtil.i18n("document.exportDrafts"),
                LangUtil.i18n("document.export"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (!isCurrentArchive(original)) return CompletableFuture.completedFuture(false);
        if (answer == 1) return exportCurrentArchive();
        if (answer != 0) return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        DraftSupport.saveAll(fileTabbedPanel).whenComplete((saved, failure) -> onEdt(() -> {
            if (failure != null || !Boolean.TRUE.equals(saved) || !isCurrentArchive(original) || hasUnappliedDrafts()) {
                result.complete(false);
                return;
            }
            exportCurrentArchive().whenComplete((exported, exportFailure) -> {
                if (exportFailure != null) result.completeExceptionally(exportFailure);
                else result.complete(exported);
            });
        }));
        return result;
    }

    private CompletableFuture<Boolean> exportCurrentArchive() {
        if (jar == null || closed) {
            MessageUtil.warning(LangUtil.i18n("workspace.open"));
            return CompletableFuture.completedFuture(false);
        }
        Jar original = jar;
        ArchiveDocumentState.Guard guard = document.captureGuard();
        File chosen = JFileChooserUtil.show(JFileChooserUtil.Type.SAVE,
                new javax.swing.filechooser.FileNameExtensionFilter("Java archive (*.jar, *.zip)", "jar", "zip"),
                JFileChooser.FILES_ONLY, suggestedOutput());
        if (chosen == null) return CompletableFuture.completedFuture(false);
        String lower = chosen.getName().toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) chosen = new File(chosen.getPath() + ".jar");
        boolean overwrite = chosen.exists();
        if (overwrite && JOptionPane.showConfirmDialog(window, LangUtil.i18n("document.overwrite", chosen.getAbsolutePath()),
                LangUtil.i18n("warning"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return CompletableFuture.completedFuture(false);
        }
        if (!isCurrentArchive(original) || !document.isCurrent(guard)) {
            notifyStatus(LangUtil.i18n("document.changedRetry"));
            return CompletableFuture.completedFuture(false);
        }
        File destination = chosen.getAbsoluteFile();
        ArchiveSnapshot snapshot = guard.content();
        return TASKS.submit(new OutputJarTask(snapshot, destination, overwrite)).thenApply(success -> {
            if (Boolean.TRUE.equals(success) && !closed && document.exported(original, snapshot)) {
                outputDestination = destination;
                refreshDocumentPresentation();
                notifyStatus(LangUtil.i18n("workspace.exported", destination.getAbsolutePath()));
            }
            return success;
        });
    }

    private File suggestedOutput() {
        if (outputDestination != null) return outputDestination;
        if (archiveSource == null) return new File("archive-edited.jar");
        String name = archiveSource.getName();
        int extension = name.lastIndexOf('.');
        if (extension > 0) name = name.substring(0, extension);
        return new File(archiveSource.getParentFile(), name + "-edited.jar");
    }

    private void requestClose() {
        if (closed || closePending) return;
        closePending = true;
        long request = beginArchiveLoad();
        authorizeReplacement(() -> isArchiveLoadCurrent(request)).whenComplete((authorization, failure) -> onEdt(() -> {
            closePending = false;
            if (failure != null || authorization == null || !isArchiveLoadCurrent(request) || !authorization.isValid()) return;
            shutdown();
            window.dispose();
        }));
    }

    private void refreshQuickOpen() {
        if (window == null) return;
        for (Window owned : window.getOwnedWindows()) {
            if (owned instanceof QuickOpenDialog dialog && dialog.isDisplayable()) dialog.refreshForArchive();
        }
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    public long beginArchiveLoad() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Archive loads must start on EDT");
        return loadRequests.begin();
    }

    public boolean isArchiveLoadCurrent(long request) {
        return loadRequests.isCurrent(request);
    }

    public boolean isCurrentArchive(Jar archive) {
        return !closed && jar == archive;
    }

    public boolean applyArchiveChange(Jar original, cn.enaium.joe.util.task.ArchiveChange change) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Archive changes must apply on EDT");
        if (!isCurrentArchive(original)) return false;
        try {
            var changedClasses = change.applyTo(original);
            refreshJar();
            for (String name : changedClasses) cn.enaium.joe.util.event.events.EditSaveSuccessEvent.trigger(name);
            return true;
        } catch (java.util.ConcurrentModificationException stale) {
            MessageUtil.warning(LangUtil.i18n("source.stale"));
            return false;
        }
    }

    public FileTabbedPanel fileTabbedPanel;

    public FileTree fileTree;

    public BottomPanel bottomPanel;

    public EventManager EVENTS;

    public final ConfigManager CONFIG;

    public TaskManager TASKS;


    public JavaOctetEditor() {
        INSTANCE = this;
        
        EVENTS = new EventManager();
        CONFIG = new ConfigManager();
        CONFIG.addByInstance(new ApplicationConfig());
        CONFIG.addByInstance(new CFRConfig());
        CONFIG.addByInstance(new VineFlowerConfig());
        CONFIG.addByInstance(new ProcyonConfig());
        CONFIG.load();
        TASKS = new TaskManager();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            TASKS.close();
            ASyncUtil.shutdown();
            CONFIG.save();
        }, "joe-shutdown"));

        Integer value = CONFIG.getByClass(ApplicationConfig.class).scale.getValue();

        if (value > 0) {
            System.setProperty("sun.java2d.uiScale", value.toString());
        }

        FlatDarkLaf.setup();
        UIManager.put("Tree.paintLines", true);

        fileTabbedPanel = new FileTabbedPanel();
        fileTree = new FileTree();
        bottomPanel = new BottomPanel();
    }

    public void run() {

        ToolTipManager.sharedInstance().setInitialDelay(0);
        FlatDarkLaf.setup();
        AbstractTokenMakerFactory abstractTokenMakerFactory = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
        abstractTokenMakerFactory.putMapping("text/custom", BytecodeTokenMaker.class.getName());
        window = new JFrame(TITLE);
        window.setIconImage(new FlatSVGIcon("icons/logo.svg").getImage());

        window.setJMenuBar(new JMenuBar() {{
            add(new FileMenu());
            add(new NavigationMenu());
            add(new SearchMenu());

            AttachMenu attachMenu = new AttachMenu() {{
                if (!ReflectionHelper.isClassExist("com.sun.tools.attach.VirtualMachine")) {
                    setEnabled(false);
                }
            }};
            add(attachMenu);
            add(new MappingMenu());
            add(new ConfigMenu());
            add(new HelpMenu());
        }});

        window.setContentPane(new BorderPanel() {{
            setCenter(workspace = new CenterPanel());
            setBottom(bottomPanel);
        }});


        window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // JFrame performs its default close operation after notifying listeners.
                // Attached launches may have set DISPOSE_ON_CLOSE; suppress it before asynchronous consent.
                window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                requestClose();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                shutdown();
            }
        });
        ApplicationConfig application = CONFIG.getByClass(ApplicationConfig.class);
        Rectangle restored = WindowGeometry.restore(application.windowX.getValue(), application.windowY.getValue(),
                application.windowWidth.getValue(), application.windowHeight.getValue(),
                virtualScreenBounds(), Util.screenSize(1000, 600));
        window.setMinimumSize(new java.awt.Dimension(640, 480));
        window.setBounds(restored);
        if (application.windowMaximized.getValue()) window.setExtendedState(JFrame.MAXIMIZED_BOTH);
        window.setVisible(true);
    }

    private static Rectangle virtualScreenBounds() {
        Rectangle bounds = new Rectangle();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(device.getDefaultConfiguration().getBounds());
        }
        return bounds;
    }

    private void rememberWindowState() {
        if (window == null || window.getWidth() <= 0 || window.getHeight() <= 0) return;
        ApplicationConfig application = CONFIG.getByClass(ApplicationConfig.class);
        boolean maximized = (window.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0;
        application.windowMaximized.setValue(maximized);
        if (maximized) return;
        Rectangle bounds = window.getBounds();
        application.windowX.setValue(bounds.x);
        application.windowY.setValue(bounds.y);
        application.windowWidth.setValue(bounds.width);
        application.windowHeight.setValue(bounds.height);
    }

    public Jar getJar() {
        return jar;
    }

    public void setJar(Jar jar) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setJar(jar));
            return;
        }
        if (closed) return;
        loadRequests.invalidate();
        this.jar = jar;
        archiveSource = null;
        outputDestination = null;
        document.replace(jar);
        fileTabbedPanel.removeAll();
        draftRevision.incrementAndGet();
        ClassTabPanel.clearPanels();
        fileTree.refresh(jar);
        if (workspace != null) workspace.refreshWorkspace();
        refreshQuickOpen();
        refreshDocumentPresentation();
    }

    public void refreshJar() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshJar);
            return;
        }
        if (jar == null || closed) return;
        jar.refresh();
        ClassTabPanel.reconcile(jar, fileTabbedPanel);
        fileTree.refresh(jar);
        refreshQuickOpen();
        markArchiveModified();
    }

    public void shutdown() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::shutdown);
            return;
        }
        if (closed) return;
        closed = true;
        rememberWindowState();
        loadRequests.close();
        TASKS.close();
        ASyncUtil.shutdown();
        bottomPanel.shutdown();
        fileTabbedPanel.removeAll();
        ClassTabPanel.clearPanels();
        CONFIG.save();
    }

    public static JavaOctetEditor getInstance() {
        return INSTANCE;
    }
}
