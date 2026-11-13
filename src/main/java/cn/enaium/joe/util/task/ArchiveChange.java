package cn.enaium.joe.util.task;

import cn.enaium.joe.jar.ArchiveSnapshot;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.classes.ClassNode;

import java.util.ArrayList;
import java.util.Objects;

/** A fully staged desktop transformation, applied only to its unchanged input. */
public final class ArchiveChange {
    private final ArchiveSnapshot baseline;
    private final Jar staged;
    private boolean applied;

    public ArchiveChange(ArchiveSnapshot baseline, Jar staged) {
        this.baseline = Objects.requireNonNull(baseline);
        staged.refresh();
        // Validate and detach script-owned trees before publication.
        this.staged = ArchiveSnapshot.capture(staged).toJar();
    }

    public java.util.List<String> applyTo(Jar target) {
        if (applied) throw new IllegalStateException("Archive change already applied");
        baseline.requireUnchanged(target);
        java.util.List<String> changedClasses = new ArrayList<>();
        for (ClassNode existing : new ArrayList<>(target.getClasses())) {
            if (!staged.hasClass(existing.getInternalName())) target.removeClass(existing.getInternalName());
        }
        for (ClassNode replacement : staged.getClasses()) {
            ClassNode existing = target.getClassNode(replacement.getInternalName());
            if (existing == null) {
                target.putClassNode(replacement);
                changedClasses.add(replacement.getInternalName());
            } else if (!java.util.Arrays.equals(existing.getClassBytes(), replacement.getClassBytes())) {
                existing.accept(replacement);
                changedClasses.add(replacement.getInternalName());
            }
        }
        for (String path : new ArrayList<>(target.getResources().keySet())) {
            if (staged.getResource(path) == null) target.removeResource(path);
        }
        staged.getResources().forEach((path, bytes) -> {
            if (!java.util.Arrays.equals(target.getResource(path), bytes)) target.putResource(path, bytes);
        });
        applied = true;
        return java.util.List.copyOf(changedClasses);
    }
}
