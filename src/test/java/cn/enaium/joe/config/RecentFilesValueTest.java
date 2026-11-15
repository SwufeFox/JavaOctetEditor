package cn.enaium.joe.config;

import cn.enaium.joe.util.config.Config;
import cn.enaium.joe.util.config.ConfigManager;
import cn.enaium.joe.util.config.value.IntegerValue;
import cn.enaium.joe.util.config.value.RecentFilesValue;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RecentFilesValueTest {
    @TempDir
    Path directory;

    public static class Settings extends Config {
        public final RecentFilesValue loadRecent = new RecentFilesValue("Load Recent", "");
        public final IntegerValue number = new IntegerValue("Number", 7, "");

        public Settings() {
            super("Application");
            postInit();
        }
    }

    private RecentFilesValue recent() {
        return new RecentFilesValue("Load Recent", "");
    }

    private ConfigManager manager(Settings settings) {
        ConfigManager manager = new ConfigManager(directory, ignored -> {});
        manager.addByInstance(settings);
        return manager;
    }

    @Test
    void successfulOpensMoveToFrontDeduplicateAndKeepOnlyTwenty() {
        RecentFilesValue recent = recent();
        for (int index = 0; index < 22; index++) recent.remember(directory.resolve(index + ".jar"));
        List<String> expected = new ArrayList<>();
        for (int index = 21; index >= 2; index--) expected.add(directory.resolve(index + ".jar").toString());
        assertEquals(expected, recent.getValue());
        recent.remember(directory.resolve("10.jar"));
        expected.remove(directory.resolve("10.jar").toString());
        expected.addFirst(directory.resolve("10.jar").toString());
        assertEquals(expected, recent.getValue());
    }

    @Test
    void snapshotsAreImmutableAndDoNotChangeAfterLaterOpens() {
        RecentFilesValue recent = recent();
        List<String> input = new ArrayList<>(List.of("first.jar"));
        recent.setValue(input);
        List<String> snapshot = recent.getValue();
        input.add("external.jar");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("mutable.jar"));
        recent.remember(directory.resolve("next.jar"));
        assertEquals(List.of("first.jar"), snapshot);
        assertEquals(List.of(directory.resolve("next.jar").toString(), "first.jar"), recent.getValue());
    }

    @Test
    void legacyArraysKeepTheirSuppliedOrderAndSpellingUntilOpened() {
        RecentFilesValue recent = recent();
        recent.decode(JsonParser.parseString("[\"z.jar\",\"a.jar\",\"z.jar\",\"folder/../old.jar\"]"));
        assertEquals(List.of("z.jar", "a.jar", "folder/../old.jar"), recent.getValue());
        recent.remember(Path.of("old.jar"));
        assertEquals(List.of(Path.of("old.jar").toAbsolutePath().normalize().toString(), "z.jar", "a.jar"),
                recent.getValue());
        recent.remember(directory.resolve("folder/../archive.jar"));
        assertEquals(directory.resolve("archive.jar").toString(), recent.getValue().getFirst());
    }

    @Test
    void legacyDecodeDeduplicatesAndCapsWithoutIgnoringMalformedTail() {
        RecentFilesValue recent = recent();
        String array = java.util.stream.IntStream.range(0, 25).mapToObj(index -> "\"" + index + ".jar\"")
                .collect(Collectors.joining(",", "[", "]"));
        recent.decode(JsonParser.parseString(array));
        assertEquals(20, recent.getValue().size());
        assertEquals("0.jar", recent.getValue().getFirst());
        assertEquals("19.jar", recent.getValue().getLast());
        List<String> before = recent.getValue();
        assertThrows(IllegalArgumentException.class,
                () -> recent.decode(JsonParser.parseString(array.substring(0, array.length() - 1) + ",false]")));
        assertEquals(before, recent.getValue());
    }

    @Test
    void persistenceKeepsMruOrderAndUsesExistingConfigurationEnvelope() throws IOException {
        Settings settings = new Settings();
        settings.loadRecent.decode(JsonParser.parseString("[\"z.jar\",\"a.jar\",\"m.jar\"]"));
        ConfigManager writer = manager(settings);
        writer.save();
        assertTrue(writer.getProblems().isEmpty());
        Path file = directory.resolve("Application.json");
        String first = Files.readString(file);
        assertEquals(JsonParser.parseString("[\"z.jar\",\"a.jar\",\"m.jar\"]"),
                JsonParser.parseString(first).getAsJsonObject().getAsJsonObject("loadRecent").get("value"));
        Settings loaded = new Settings();
        ConfigManager reader = manager(loaded);
        reader.load();
        assertTrue(reader.getProblems().isEmpty());
        assertEquals(settings.loadRecent.getValue(), loaded.loadRecent.getValue());
        reader.save();
        assertTrue(reader.getProblems().isEmpty());
        assertEquals(first, Files.readString(file));
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void malformedRecentSettingIsAtomicAndPreservedWhileOtherSettingsLoad() throws IOException {
        for (String malformed : List.of("[\"valid.jar\",42]", "[null]", "null", "{}", "\"archive.jar\"")) {
            String original = "{\"loadRecent\":{\"value\":" + malformed + "},\"number\":{\"value\":19}}";
            Path file = directory.resolve("Application.json");
            Files.writeString(file, original);
            Settings settings = new Settings();
            settings.loadRecent.setValue(List.of("existing.jar"));
            ConfigManager manager = manager(settings);
            manager.load();
            assertEquals(List.of("existing.jar"), settings.loadRecent.getValue());
            assertEquals(19, settings.number.getValue());
            assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("Application.loadRecent")));
            assertEquals(original, Files.readString(file));
            try (var paths = Files.list(directory)) {
                assertTrue(paths.filter(path -> path.getFileName().toString().startsWith("Application.json.recovery-"))
                        .anyMatch(path -> {
                            try {
                                return Files.readString(path).equals(original);
                            } catch (IOException error) {
                                throw new AssertionError(error);
                            }
                        }));
            }
            manager.save();
            assertTrue(manager.getProblems().isEmpty());
        }
    }

    @Test
    void cleanupRemovesOnlyObservedMissingShortcutsAndPreservesNewerOpens() throws IOException {
        Path valid = Files.writeString(directory.resolve("valid.jar"), "archive bytes");
        Path missing = directory.resolve("missing.jar");
        Path reopened = directory.resolve("reopened.jar");
        RecentFilesValue recent = recent();
        recent.remember(valid);
        recent.remember(missing);
        recent.remember(reopened);
        RecentFilesValue.CleanupSnapshot snapshot = recent.cleanupSnapshot();
        Set<String> observedMissing = snapshot.paths().stream().filter(path -> Files.notExists(Path.of(path)))
                .collect(Collectors.toSet());
        // The probe has already finished when these opens are published.
        recent.remember(reopened);
        Path newer = directory.resolve("newer.jar");
        recent.remember(newer);
        recent.removeMissing(snapshot, observedMissing);
        assertEquals(List.of(newer.toString(), reopened.toString(), valid.toString()), recent.getValue());
        assertEquals("archive bytes", Files.readString(valid));
        assertFalse(Files.exists(missing));
        assertEquals(List.of(reopened.toString(), missing.toString(), valid.toString()), snapshot.paths());
    }

    @Test
    void cleanupCannotRemovePathsOutsideItsSnapshotOrReplacedConfiguration() {
        RecentFilesValue recent = recent();
        recent.setValue(List.of("old.jar"));
        RecentFilesValue.CleanupSnapshot snapshot = recent.cleanupSnapshot();
        recent.setValue(List.of("old.jar", "new.jar"));
        recent.removeMissing(snapshot, Set.of("old.jar", "new.jar"));
        assertEquals(List.of("old.jar", "new.jar"), recent.getValue());
    }
}
