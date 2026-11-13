package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.compiler.CompileError;
import cn.enaium.joe.util.compiler.Compiler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Shared source-edit preparation and optimistic commit for desktop and tools. */
public final class ArchiveCompilation {
    private static final BooleanSupplier NOT_CANCELLED = () -> false;

    public record Change(boolean changed, List<String> generatedClasses, List<String> removedClasses) {
        public Change {
            generatedClasses = List.copyOf(generatedClasses);
            removedClasses = List.copyOf(removedClasses);
        }
    }

    private final ArchiveSnapshot baseline;
    private final Map<String, ClassNode> updates;
    private final Change change;
    private boolean applied;

    private ArchiveCompilation(ArchiveSnapshot baseline, Map<String, ClassNode> updates, Change change) {
        this.baseline = baseline;
        this.updates = updates;
        this.change = change;
    }

    /** Runs off the owning thread and never mutates the live archive. */
    public static ArchiveCompilation compile(ArchiveSnapshot baseline, Map<String, String> sources) throws CompileError {
        Objects.requireNonNull(baseline, "baseline");
        Map<String, String> inputs = new LinkedHashMap<>(Objects.requireNonNull(sources, "sources"));
        Map<String, byte[]> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : baseline.classBytes().entrySet()) {
            ArchiveSnapshot.checkInterrupted();
            if (!entry.getKey().equals("module-info")) {
                dependencies.put(entry.getKey().replace('/', '.'), entry.getValue());
            }
        }
        Map<String, byte[]> output = Compiler.compileSources(inputs, dependencies);
        Set<String> roots = new HashSet<>();
        for (String name : inputs.keySet()) {
            if (!output.containsKey(name)) {
                throw new CompileError("Source must declare the requested class: " + name);
            }
            roots.add(name.replace('.', '/'));
        }

        Map<String, ClassNode> updates = new TreeMap<>();
        Set<String> generated = new TreeSet<>();
        for (Map.Entry<String, byte[]> entry : output.entrySet()) {
            ArchiveSnapshot.checkInterrupted();
            String name = entry.getKey().replace('.', '/');
            byte[] bytes = entry.getValue();
            if (bytes == null) throw new CompileError("Compiler produced no bytecode for " + entry.getKey());
            generated.add(name);
            if (Arrays.equals(baseline.classBytes().get(name), bytes)) continue;
            try {
                ClassNode node = ClassNode.of(bytes);
                if (!node.getInternalName().equals(name)) {
                    throw new CompileError("Compiler output name mismatch: " + entry.getKey());
                }
                updates.put(name, node);
            } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
                throw new CompileError("Invalid compiler output for " + entry.getKey(), failure);
            }
        }

        List<String> removed = new ArrayList<>();
        for (String name : baseline.classBytes().keySet()) {
            if (!generated.contains(name) && baseline.belongsToSource(name, roots)) {
                removed.add(name);
            }
        }
        removed.sort(String::compareTo);
        Change change = new Change(!updates.isEmpty() || !removed.isEmpty(), List.copyOf(generated), removed);
        return new ArchiveCompilation(baseline, updates, change);
    }

    public Change applyTo(Jar target) {
        return applyTo(target, NOT_CANCELLED);
    }

    /**
     * Commits once on the model's owning thread. Cooperative commits to the same
     * Jar are serialized; unrelated live ASM mutations must remain owner-bound.
     * The explicit token also works when third-party code cleared interruption.
     */
    public synchronized Change applyTo(Jar target, BooleanSupplier cancelled) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cancelled, "cancelled");
        if (applied) throw new IllegalStateException("This compilation result has already been applied");
        checkCancelled(cancelled);
        synchronized (target) {
            baseline.requireUnchanged(target);
            checkCancelled(cancelled);
            if (change.changed()) {
                for (String name : change.removedClasses()) target.removeClass(name);
                for (Map.Entry<String, ClassNode> entry : updates.entrySet()) {
                    ClassNode existing = target.getClassNode(entry.getKey());
                    if (existing == null) target.putClassNode(entry.getValue());
                    else existing.accept(entry.getValue());
                }
            }
            applied = true;
            return change;
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        ArchiveSnapshot.checkInterrupted();
        if (cancelled.getAsBoolean()) throw new CancellationException("Archive compilation cancelled before commit");
    }
}
