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
    private static final class Match {
        private Indexed indexed;
        private int rank;
        private int distance;

        private Match(Indexed indexed, long score) {
            set(indexed, score);
        }

        private void set(Indexed indexed, long score) {
            this.indexed = indexed;
            rank = (int) (score >>> 32);
            distance = (int) score;
        }
    }

    private static final Comparator<Indexed> PATH_ORDER = Comparator
            .comparing(Indexed::path)
            .thenComparing(value -> value.entry().path())
            .thenComparing(value -> value.entry().kind());
    private static final Comparator<Match> MATCH_ORDER = (left, right) ->
            compare(left.indexed, left.rank, left.distance, right);
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
        String text = normalize(query);
        String[] tokens = tokens(text);
        int capacity = (int) Math.min((long) offset + limit, entries.size());
        boolean bounded = capacity < entries.size();
        List<Match> matches = new ArrayList<>();
        PriorityQueue<Match> best = bounded
                ? new PriorityQueue<>(Math.min(capacity, 64), MATCH_ORDER.reversed()) : null;
        int total = 0;
        for (Indexed entry : entries) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!kinds.contains(entry.entry().kind())) continue;
            long score = score(entry, text, tokens);
            if (score < 0) continue;
            total++;
            if (!bounded) {
                matches.add(new Match(entry, score));
            } else if (best.size() < capacity) {
                best.add(new Match(entry, score));
            } else if (compare(entry, (int) (score >>> 32), (int) score, best.peek()) < 0) {
                Match replacement = best.remove();
                replacement.set(entry, score);
                best.add(replacement);
            }
        }
        if (bounded) matches.addAll(best);
        matches.sort(MATCH_ORDER);
        int start = Math.min(offset, total);
        int end = (int) Math.min((long) start + limit, total);
        List<Entry> result = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) result.add(matches.get(i).indexed.entry());
        return new Page(result, total, end < total, end);
    }

    /** All matching names in capture order, without ranking or sorting. */
    public List<Entry> matchingEntries(String query, Set<Kind> kinds) {
        Objects.requireNonNull(kinds, "kinds");
        String text = normalize(query);
        String[] tokens = tokens(text);
        List<Entry> result = new ArrayList<>();
        for (Indexed entry : entries) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (kinds.contains(entry.entry().kind()) && score(entry, text, tokens) >= 0)
                result.add(entry.entry());
        }
        return List.copyOf(result);
    }

    private static String normalize(String query) {
        return Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
    }

    private static String[] tokens(String text) {
        return text.isEmpty() ? new String[0] : text.split("\\s+");
    }

    // Pack the rank and signed distance without allocating a result for every candidate.
    private static long score(Indexed entry, String text, String[] tokens) {
        int rank = rank(entry, text);
        int distance = 0;
        if (rank < 0) {
            rank = 4;
            for (String token : tokens) {
                if (entry.path().contains(token) || entry.dotted().contains(token)) continue;
                int gap = Math.min(subsequence(entry.path(), token), subsequence(entry.dotted(), token));
                if (gap == Integer.MAX_VALUE) return -1;
                rank = 5;
                distance += gap;
            }
        }
        return ((long) rank << 32) | Integer.toUnsignedLong(distance);
    }

    private static int compare(Indexed indexed, int rank, int distance, Match other) {
        int order = Integer.compare(rank, other.rank);
        if (order == 0) order = Integer.compare(distance, other.distance);
        if (order == 0) order = PATH_ORDER.compare(indexed, other.indexed);
        return order;
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
