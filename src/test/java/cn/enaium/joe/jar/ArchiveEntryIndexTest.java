package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.*;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveEntryIndexTest {
    private static final Set<ArchiveEntryIndex.Kind> ALL = EnumSet.allOf(ArchiveEntryIndex.Kind.class);

    @Test
    void dottedClassesAndFullArchivePathsResolveToTheSameEntry() {
        Jar archive = new Jar();
        archive.putClassNode(node("org/example/Widget$Nested"));
        archive.putResource("org/example/Widget.properties", new byte[0]);
        ArchiveEntryIndex index = ArchiveEntryIndex.capture(archive);

        for (String query : List.of("org.example.Widget$Nested", "org/example/Widget$Nested.class",
                "Widget$Nested", "Widget$Nested.class")) {
            var page = index.search(query, ALL, 0, 10);
            assertEquals(1, page.total());
            assertEquals(new ArchiveEntryIndex.Entry(ArchiveEntryIndex.Kind.CLASS,
                    "org/example/Widget$Nested.class", "Widget$Nested"), page.entries().getFirst());
        }
        assertEquals(0, index.search("Widget$Nested", EnumSet.of(ArchiveEntryIndex.Kind.RESOURCE), 0, 10).total());
        assertEquals("org/example/Widget.properties", index.search("Widget", EnumSet.of(ArchiveEntryIndex.Kind.RESOURCE),
                0, 10).entries().getFirst().path());
    }

    @Test
    void exactNamesPrefixesAndSubstringsPrecedeFuzzyMatches() {
        Jar archive = new Jar();
        for (String name : List.of("a/FastOpaqueObject", "a/SomeFoo", "FooPackage/Thing", "a/Foobar", "z/Foo"))
            archive.putClassNode(node(name));

        assertEquals(List.of("z/Foo.class", "a/Foobar.class", "FooPackage/Thing.class", "a/SomeFoo.class",
                        "a/FastOpaqueObject.class"),
                paths(ArchiveEntryIndex.capture(archive).search("FOO", ALL, 0, 10)));
    }

    @Test
    void tokenAndFuzzySearchKeepPunctuationLiteral() {
        Jar archive = new Jar();
        archive.putResource("META-INF/config.properties", new byte[0]);
        archive.putResource("docs/config.txt", new byte[0]);
        archive.putResource("assets/[build]+(x).json", new byte[0]);
        ArchiveEntryIndex index = ArchiveEntryIndex.capture(archive);

        assertEquals(List.of("META-INF/config.properties"), paths(index.search("meta config", ALL, 0, 10)));
        assertEquals(List.of("META-INF/config.properties"), paths(index.search("meta cfg", ALL, 0, 10)));
        assertEquals(List.of("assets/[build]+(x).json"), paths(index.search("[build]+(x).json", ALL, 0, 10)));
        assertEquals(0, index.search(".*", ALL, 0, 10).total());
        assertEquals(0, index.search("[build]+(y).json", ALL, 0, 10).total());
    }

    @Test
    void pagesAreDeterministicIndependentOfInsertionAndExcludeDirectories() {
        Jar first = new Jar();
        Jar second = new Jar();
        List<String> names = List.of("z.txt", "alpha.txt", "Alpha.txt", "nested/file.txt", "empty/");
        names.forEach(name -> first.putResource(name, new byte[0]));
        names.reversed().forEach(name -> second.putResource(name, new byte[0]));
        ArchiveEntryIndex index = ArchiveEntryIndex.capture(first);
        var firstPage = index.search("", ALL, 0, 2);
        var secondPage = index.search("", ALL, firstPage.nextOffset(), 2);

        assertEquals(List.of("Alpha.txt", "alpha.txt"), paths(firstPage));
        assertEquals(List.of("nested/file.txt", "z.txt"), paths(secondPage));
        assertEquals(4, firstPage.total());
        assertTrue(firstPage.hasMore());
        assertFalse(secondPage.hasMore());
        assertEquals(4, secondPage.nextOffset());
        assertEquals(firstPage, ArchiveEntryIndex.capture(second).search("", ALL, 0, 2));
        var beyondEnd = index.search("", ALL, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(List.of(), beyondEnd.entries());
        assertEquals(4, beyondEnd.nextOffset());
        assertFalse(beyondEnd.hasMore());
        assertEquals(4, index.search(" ", ALL, 0, Integer.MAX_VALUE).entries().size());
        assertEquals(0, index.search("", Set.of(), 0, 10).total());
        assertThrows(IllegalArgumentException.class, () -> index.search("", ALL, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> index.search("", ALL, 0, 0));
    }

    @Test
    void boundedPagesKeepAllRanksAndFuzzyDistancesWhenBetterHitsArriveLate() {
        Jar archive = new Jar();
        for (String name : List.of("a/FastOpaqueObject", "a/FarOutObject", "a/SomeFoo", "FooPackage/Thing",
                "a/Foobar", "z/Foo", "A/Foo")) archive.putClassNode(node(name));
        for (int i = 0; i < 40; i++) archive.putResource("unrelated/" + i + ".txt", new byte[0]);
        ArchiveEntryIndex index = ArchiveEntryIndex.capture(archive);
        List<String> expected = List.of("A/Foo.class", "z/Foo.class", "a/Foobar.class", "FooPackage/Thing.class",
                "a/SomeFoo.class", "a/FarOutObject.class", "a/FastOpaqueObject.class");

        for (int offset = 0; offset <= expected.size() + 2; offset++) {
            var page = index.search("foo", ALL, offset, 2);
            int start = Math.min(offset, expected.size());
            int end = Math.min(start + 2, expected.size());
            assertEquals(expected.subList(start, end), paths(page), "offset " + offset);
            assertEquals(expected.size(), page.total());
            assertEquals(end, page.nextOffset());
            assertEquals(end < expected.size(), page.hasMore());
        }
        assertEquals(expected.subList(2, expected.size()), paths(index.search("foo", ALL, 2, Integer.MAX_VALUE)));
        assertEquals(expected.size(), index.search("foo", ALL, Integer.MAX_VALUE, 1).nextOffset());
    }

    @Test
    void boundedPagesKeepCaseAndKindTiesDeterministic() {
        Jar archive = new Jar();
        archive.putResource("a/Foo.class", new byte[0]);
        archive.putClassNode(node("a/Foo"));
        archive.putResource("A/Foo.class", new byte[0]);
        archive.putResource("other.txt", new byte[0]);
        ArchiveEntryIndex index = ArchiveEntryIndex.capture(archive);

        assertEquals(new ArchiveEntryIndex.Entry(ArchiveEntryIndex.Kind.RESOURCE, "A/Foo.class", "Foo.class"),
                index.search("foo.class", ALL, 0, 1).entries().getFirst());
        assertEquals(ArchiveEntryIndex.Kind.CLASS, index.search("foo.class", ALL, 1, 1).entries().getFirst().kind());
        assertEquals(ArchiveEntryIndex.Kind.RESOURCE, index.search("foo.class", ALL, 2, 1).entries().getFirst().kind());
    }

    @Test
    void unrankedFilteringFindsEveryRankedMatchAndRetainsCaptureOrder() {
        Jar archive = new Jar();
        archive.putClassNode(node("org/example/FastOpaqueObject"));
        archive.putClassNode(node("org/example/Foo"));
        archive.putResource("META-INF/config.properties", new byte[0]);
        archive.putResource("assets/[build]+(x).json", new byte[0]);
        archive.putResource("empty/", new byte[0]);
        ArchiveEntryIndex index = ArchiveEntryIndex.capture(archive);

        for (String query : List.of("", "foo", "org foo", "org.example.Foo", "meta cfg", "[build]+(x).json", "missing")) {
            for (Set<ArchiveEntryIndex.Kind> kinds : List.of(ALL, EnumSet.of(ArchiveEntryIndex.Kind.CLASS),
                    EnumSet.of(ArchiveEntryIndex.Kind.RESOURCE), EnumSet.noneOf(ArchiveEntryIndex.Kind.class))) {
                assertEquals(new HashSet<>(index.search(query, kinds, 0, Integer.MAX_VALUE).entries()),
                        new HashSet<>(index.matchingEntries(query, kinds)), query);
            }
        }
        var filtered = index.matchingEntries("foo", ALL);
        assertEquals(List.of("org/example/FastOpaqueObject.class", "org/example/Foo.class", "META-INF/config.properties"),
                filtered.stream().map(ArchiveEntryIndex.Entry::path).toList());
        archive.removeClass("org/example/Foo");
        assertEquals(filtered, index.matchingEntries("foo", ALL));
        assertThrows(UnsupportedOperationException.class, filtered::clear);
    }

    @Test
    void successfulRenameInvalidatesNavigationWithoutMutatingPreviouslyCapturedNames() {
        Jar archive = new Jar();
        ClassNode original = node("before/Old");
        archive.putClassNode(original);
        ArchiveEntryIndex before = ArchiveEntryIndex.capture(archive);
        long revision = archive.getStructuralRevision();
        original.getClassNode().name = "after/New";
        archive.refresh();

        assertNotEquals(revision, archive.getStructuralRevision());
        assertSame(original, archive.getClassNode("after/New"));
        assertEquals(List.of("before/Old.class"), paths(before.search("", ALL, 0, 10)));
        ArchiveEntryIndex after = ArchiveEntryIndex.capture(archive);
        assertEquals(List.of("after/New.class"), paths(after.search("", ALL, 0, 10)));
        assertEquals(List.of(), after.matchingEntries("before/Old", ALL));
    }

    @Test
    void capturedNamesAndResultsStayImmutableAfterArchiveChanges() {
        Jar archive = new Jar();
        archive.putClassNode(node("before/Old"));
        archive.putResource("original.txt", new byte[] {1});
        ArchiveEntryIndex index = ArchiveEntryIndex.capture(archive);
        archive.removeClass("before/Old");
        archive.putClassNode(node("after/New"));
        archive.removeResource("original.txt");
        archive.putResource("replacement.txt", new byte[] {2});

        var page = index.search("", ALL, 0, 10);
        assertEquals(List.of("before/Old.class", "original.txt"), paths(page));
        assertThrows(UnsupportedOperationException.class, () -> page.entries().clear());
        assertEquals(0, index.search("replacement", ALL, 0, 10).total());
    }

    @Test
    void searchUsesRootLocaleAndSupportsUnicodePaths() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Jar archive = new Jar();
            archive.putResource("INDEX/配置.json", new byte[0]);
            archive.putClassNode(node("示例/工具"));
            ArchiveEntryIndex index = ArchiveEntryIndex.capture(archive);
            assertEquals(List.of("INDEX/配置.json"), paths(index.search("index/配置", ALL, 0, 10)));
            assertEquals(List.of("示例/工具.class"), paths(index.search("示例.工具", ALL, 0, 10)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void interruptedSearchStopsWithoutReturningPartialResults() {
        Jar archive = new Jar();
        archive.putResource("file.txt", new byte[0]);
        ArchiveEntryIndex index = ArchiveEntryIndex.capture(archive);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> index.search("", ALL, 0, 10));
            assertThrows(CancellationException.class, () -> index.matchingEntries("", ALL));
        } finally {
            Thread.interrupted();
        }
    }

    private static List<String> paths(ArchiveEntryIndex.Page page) {
        return page.entries().stream().map(ArchiveEntryIndex.Entry::path).toList();
    }

    private static ClassNode node(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return ClassNode.of(writer.toByteArray());
    }
}
