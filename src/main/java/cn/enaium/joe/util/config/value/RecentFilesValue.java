package cn.enaium.joe.util.config.value;

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;

/** Ordered recent-file shortcuts. Reading a value never exposes mutable state. */
public final class RecentFilesValue extends Value<List<String>> {
    public static final int LIMIT = 20;
    private long revision;
    private final Map<String, Long> remembered = new HashMap<>();

    public RecentFilesValue(String name, String description) {
        super(TypeToken.getParameterized(List.class, String.class).getType(), name, List.of(), description);
    }

    @Override
    public synchronized void setValue(List<String> paths) {
        List<String> snapshot = ordered(paths);
        long next = ++revision;
        remembered.clear();
        snapshot.forEach(path -> remembered.put(path, next));
        super.setValue(snapshot);
    }

    /** Record only successful opens; normalization is lexical and performs no filesystem I/O. */
    public synchronized void remember(Path path) {
        Path normalized = Objects.requireNonNull(path).toAbsolutePath().normalize();
        String spelling = normalized.toString();
        List<String> next = new ArrayList<>();
        next.add(spelling);
        for (String previous : getValue()) {
            if (!samePath(previous, normalized) && next.size() < LIMIT) next.add(previous);
        }
        remembered.keySet().retainAll(next);
        remembered.put(spelling, ++revision);
        super.setValue(List.copyOf(next));
    }

    /** Capture before background probing; later successful opens must survive its publication. */
    public synchronized CleanupSnapshot cleanupSnapshot() {
        return new CleanupSnapshot(getValue(), revision);
    }

    public synchronized void removeMissing(CleanupSnapshot snapshot, Set<String> missing) {
        Set<String> candidates = new HashSet<>(snapshot.paths());
        candidates.retainAll(missing);
        List<String> next = new ArrayList<>(getValue());
        next.removeIf(path -> candidates.contains(path)
                && remembered.getOrDefault(path, Long.MAX_VALUE) <= snapshot.revision());
        if (!next.equals(getValue())) {
            remembered.keySet().retainAll(next);
            super.setValue(List.copyOf(next));
        }
    }

    public record CleanupSnapshot(List<String> paths, long revision) {
        public CleanupSnapshot {
            paths = List.copyOf(paths);
        }
    }

    @Override
    public void decode(JsonElement jsonElement) {
        if (jsonElement == null || !jsonElement.isJsonArray()) {
            throw new IllegalArgumentException("Expected an array of strings");
        }
        List<String> paths = new ArrayList<>();
        for (JsonElement element : jsonElement.getAsJsonArray()) {
            requirePrimitive(element, "string");
            paths.add(element.getAsString());
        }
        setValue(paths);
    }

    private static List<String> ordered(List<String> paths) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String path : Objects.requireNonNull(paths)) {
            Objects.requireNonNull(path);
            if (unique.size() < LIMIT) unique.add(path);
        }
        return List.copyOf(unique);
    }

    private static boolean samePath(String previous, Path normalized) {
        try {
            return Path.of(previous).toAbsolutePath().normalize().equals(normalized);
        } catch (InvalidPathException ignored) {
            // Imported strings remain untouched until explicitly removed by the user.
            return false;
        }
    }
}
