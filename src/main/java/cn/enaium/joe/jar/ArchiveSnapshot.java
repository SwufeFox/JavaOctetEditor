package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Owned archive content captured on the model's owning thread. Background work
 * must use this snapshot rather than retain live ASM trees or resource arrays.
 */
public final class ArchiveSnapshot {
    private final Map<String, byte[]> classes;
    private final Map<String, byte[]> resources;
    private final Map<String, String> enclosingClasses;

    private ArchiveSnapshot(Map<String, byte[]> classes, Map<String, byte[]> resources,
                            Map<String, String> enclosingClasses) {
        this.classes = Collections.unmodifiableMap(classes);
        this.resources = Collections.unmodifiableMap(resources);
        this.enclosingClasses = Collections.unmodifiableMap(enclosingClasses);
    }

    public static ArchiveSnapshot capture(Jar archive) {
        Objects.requireNonNull(archive, "archive");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, String> enclosingClasses = new LinkedHashMap<>();
        for (ClassNode node : archive.getClasses()) {
            checkInterrupted();
            String name = node.getInternalName();
            if (archive.getClassNode(name) != node || classes.containsKey(name)) {
                throw new IllegalStateException("Archive class index is inconsistent. Refresh class names before capturing a snapshot: " + name);
            }
            classes.put(name, node.getClassBytes().clone());
            var tree = node.getNodeInternal();
            String enclosing = tree.outerClass;
            if (enclosing == null) {
                for (var inner : tree.innerClasses) {
                    if (name.equals(inner.name) && inner.outerName != null) {
                        enclosing = inner.outerName;
                        break;
                    }
                }
            }
            if (enclosing != null) enclosingClasses.put(name, enclosing);
        }
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : archive.getResources().entrySet()) {
            checkInterrupted();
            resources.put(entry.getKey(), entry.getValue().clone());
        }
        return new ArchiveSnapshot(classes, resources, enclosingClasses);
    }

    /** Returns independent mutable state; editing it cannot alter the baseline. */
    public Jar toJar() {
        Jar copy = new Jar();
        for (byte[] bytes : classes.values()) {
            checkInterrupted();
            copy.putClassNode(ClassNode.of(bytes));
        }
        for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
            checkInterrupted();
            copy.putResource(entry.getKey(), entry.getValue().clone());
        }
        return copy;
    }

    /** Must run on the model's owning thread or under its external lock. */
    public boolean matches(Jar archive) {
        Objects.requireNonNull(archive, "archive");
        if (classes.size() != archive.getClassSize() || resources.size() != archive.getResourceSize()) {
            return false;
        }
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            checkInterrupted();
            ClassNode node = archive.getClassNode(entry.getKey());
            if (node == null || !entry.getKey().equals(node.getInternalName())
                    || !Arrays.equals(entry.getValue(), node.getClassBytes())) {
                return false;
            }
        }
        for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
            checkInterrupted();
            if (!Arrays.equals(entry.getValue(), archive.getResource(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    public void requireUnchanged(Jar archive) {
        if (!matches(archive)) {
            throw new ConcurrentModificationException("The archive changed while this operation was running. Review the current archive and retry; no result was applied.");
        }
    }

    // Trusted compilation code in this package reads these bytes without another
    // full archive copy. No mutable byte arrays escape through the public API.
    Map<String, byte[]> classBytes() {
        return classes;
    }

    boolean belongsToSource(String name, java.util.Set<String> sources) {
        String owner = enclosingClasses.get(name);
        for (int remaining = enclosingClasses.size(); owner != null && remaining-- > 0; ) {
            if (sources.contains(owner)) return true;
            owner = enclosingClasses.get(owner);
        }
        return false;
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Archive operation cancelled");
        }
    }
}
