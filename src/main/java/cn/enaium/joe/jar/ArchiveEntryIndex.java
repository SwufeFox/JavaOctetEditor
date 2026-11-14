package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Immutable archive names, safe to search away from the model's owning thread. */
public final class ArchiveEntryIndex {
    public enum Kind { CLASS, RESOURCE }

    public record Entry(Kind kind, String path, String displayName) { }

    public record Page(List<Entry> entries, int total, boolean hasMore, int nextOffset) {
        public Page {
            entries = List.copyOf(entries);
        }
    }

    private record Indexed(Entry entry, String path, String name, String fileName, String dotted, String stem) { }
    private record Match(Indexed indexed, int rank, int distance) { }

    private static final Comparator<Indexed> PATH_ORDER = Comparator
            .comparing(Indexed::path)
            .thenComparing(value -> value.entry().path())
            .thenComparing(value -> value.entry().kind());
    private final List<Indexed> entries;

    private ArchiveEntryIndex(List<Indexed> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Capture on the archive's owning thread. Does not read or serialize entry bytes. */
    public static ArchiveEntryIndex capture(Jar archive) {
        Objects.requireNonNull(archive, "archive");
        List<Indexed> entries = new ArrayList<>(archive.getClassSize() + archive.getResourceSize());
        for (ClassNode node : archive.getClasses()) {
            String name = node.getInternalName();
            add(entries, Kind.CLASS, name + ".class", name.substring(name.lastIndexOf('/') + 1));
        }
        for (String path : archive.getResources().keySet()) {
            if (path.endsWith("/")) continue;
            add(entries, Kind.RESOURCE, path, path.substring(path.lastIndexOf('/') + 1));
        }
        return new ArchiveEntryIndex(entries);
    }

    private static void add(List<Indexed> entries, Kind kind, String path, String name) {
        String normalized = path.toLowerCase(Locale.ROOT);
        String stem = kind == Kind.CLASS ? normalized.substring(0, normalized.length() - 6) : normalized;
        entries.add(new Indexed(new Entry(kind, path, name), normalized,
                name.toLowerCase(Locale.ROOT), normalized.substring(normalized.lastIndexOf('/') + 1),
                kind == Kind.CLASS ? stem.replace('/', '.') : normalized, stem));
    }

    /** Exact names precede prefixes, literal substrings, token matches and fuzzy subsequences. */
    public Page search(String query, Set<Kind> kinds, int offset, int limit) {
        Objects.requireNonNull(kinds, "kinds");
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("offset must be nonnegative and limit positive");
        String text = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
        String[] tokens = text.isEmpty() ? new String[0] : text.split("\\s+");
        List<Match> matches = new ArrayList<>();
        for (Indexed entry : entries) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!kinds.contains(entry.entry().kind())) continue;
            int rank = rank(entry, text);
            int distance = 0;
            if (rank < 0) {
                rank = 4;
                for (String token : tokens) {
                    if (entry.path().contains(token) || entry.dotted().contains(token)) continue;
                    int gap = Math.min(subsequence(entry.path(), token), subsequence(entry.dotted(), token));
                    if (gap == Integer.MAX_VALUE) {
                        rank = -1;
                        break;
                    }
                    rank = 5;
                    distance += gap;
                }
            }
            if (rank >= 0) matches.add(new Match(entry, rank, distance));
        }
        matches.sort(Comparator.comparingInt(Match::rank).thenComparingInt(Match::distance)
                .thenComparing(Match::indexed, PATH_ORDER));
        int start = Math.min(offset, matches.size());
        int end = (int) Math.min((long) start + limit, matches.size());
        List<Entry> result = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) result.add(matches.get(i).indexed().entry());
        return new Page(result, matches.size(), end < matches.size(), end);
    }

    private static int rank(Indexed entry, String text) {
        if (text.isEmpty()) return 0;
        if (entry.name().equals(text) || entry.fileName().equals(text) || entry.path().equals(text)
                || entry.stem().equals(text) || entry.dotted().equals(text)) return 0;
        if (entry.name().startsWith(text) || entry.fileName().startsWith(text)) return 1;
        if (entry.path().startsWith(text) || entry.dotted().startsWith(text)) return 2;
        if (entry.path().contains(text) || entry.dotted().contains(text)) return 3;
        return -1;
    }

    private static int subsequence(String candidate, String token) {
        int next = 0;
        int first = -1;
        int last = -1;
        for (int i = 0; i < token.length(); i++) {
            int found = candidate.indexOf(token.charAt(i), next);
            if (found < 0) return Integer.MAX_VALUE;
            if (first < 0) first = found;
            last = found;
            next = found + 1;
        }
        return last - first + 1 - token.length();
    }
}
