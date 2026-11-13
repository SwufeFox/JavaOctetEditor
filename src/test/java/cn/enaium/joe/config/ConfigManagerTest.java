package cn.enaium.joe.config;

import cn.enaium.joe.util.config.Config;
import cn.enaium.joe.util.config.ConfigManager;
import cn.enaium.joe.util.config.value.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigManagerTest {
    @TempDir
    Path directory;

    enum Choice { FIRST, SECOND, DISALLOWED }

    public static class Settings extends Config {
        public final EnableValue enabled = new EnableValue("Enabled", true, "");
        public final IntegerValue number = new IntegerValue("Number", 7, "");
        public final StringValue text = new StringValue("Text", "default", "");
        public final StringValue optional = new StringValue("Optional", null, "");
        public final ModeValue<Choice> mode = new ModeValue<>("Mode", Choice.FIRST, "",
                EnumSet.of(Choice.FIRST, Choice.SECOND));
        public final KeyValue key = new KeyValue("Key", KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK), "");
        public final StringSetValue recent = new StringSetValue("Recent", Set.of("default.jar"), "");
        public final ConfigValue<Nested> nested = new ConfigValue<>(new Nested(), "");

        Settings() {
            super("Settings");
            postInit();
        }
    }

    public static class Nested extends Config {
        public final IntegerValue number = new IntegerValue("Nested number", 11, "");
        public final EnableValue enabled = new EnableValue("Nested enabled", true, "");

        Nested() {
            super("Nested");
            postInit();
        }
    }

    private ConfigManager manager(Settings settings) {
        ConfigManager manager = new ConfigManager(directory, message -> {});
        manager.addByInstance(settings);
        return manager;
    }

    private Path file() {
        return directory.resolve("Settings.json");
    }

    private List<Path> recoveries() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("Settings.json.recovery-"))
                    .toList();
        }
    }

    @Test
    void partialSettingsKeepDefaultsAndDoNotReportMissingFields() throws IOException {
        Files.writeString(file(), "{\"text\":{\"value\":\"loaded\"}}");
        Settings settings = new Settings();
        ConfigManager manager = manager(settings);
        manager.load();
        assertEquals("loaded", settings.text.getValue());
        assertEquals(7, settings.number.getValue());
        assertEquals(Choice.FIRST, settings.mode.getValue());
        assertTrue(manager.getProblems().isEmpty());
        assertTrue(recoveries().isEmpty());
    }

    @Test
    void oneDamagedSettingDoesNotSkipOtherValidSettingsOrNestedSettings() throws IOException {
        String original = """
                {"enabled":{"value":"false"},"number":{"value":42},
                 "mode":{"value":"REMOVED"},"text":{"value":"kept"},
                 "key":{"value":"not a key binding"},
                 "recent":{"value":["valid.jar",42]},
                 "nested":{"value":{"number":{"value":35},"enabled":{"value":"bad"}}}}
                """;
        Files.writeString(file(), original);
        Settings settings = new Settings();
        ConfigManager manager = manager(settings);
        KeyStroke defaultKey = settings.key.getValue();
        manager.load();
        assertTrue(settings.enabled.getValue());
        assertEquals(42, settings.number.getValue());
        assertEquals("kept", settings.text.getValue());
        assertEquals(Choice.FIRST, settings.mode.getValue());
        assertEquals(defaultKey, settings.key.getValue());
        assertEquals(Set.of("default.jar"), settings.recent.getValue());
        assertEquals(35, settings.nested.getValue().number.getValue());
        assertTrue(settings.nested.getValue().enabled.getValue());
        assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("Settings.nested.enabled")));
        assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("Settings.key")));
        assertEquals(original, Files.readString(file()));
        assertEquals(1, recoveries().size());
        assertEquals(original, Files.readString(recoveries().getFirst()));
        manager.save();
        assertTrue(manager.getProblems().isEmpty());
        assertEquals(original, Files.readString(recoveries().getFirst()));
        assertTrue(JsonParser.parseString(Files.readString(file())).isJsonObject());
    }

    @Test
    void malformedAndNonObjectFilesArePreservedBeforeSavingDefaults() throws IOException {
        for (String original : List.of("{\"number\":", "[]", "null", "", "{} trailing")) {
            Files.writeString(file(), original);
            Settings settings = new Settings();
            ConfigManager manager = manager(settings);
            int previousBackups = recoveries().size();
            manager.load();
            assertEquals(7, settings.number.getValue());
            assertFalse(manager.getProblems().isEmpty());
            assertEquals(original, Files.readString(file()));
            List<Path> backups = recoveries();
            assertEquals(previousBackups + 1, backups.size());
            assertTrue(backups.stream().anyMatch(path -> {
                try {
                    return Files.readString(path).equals(original);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }));
            manager.save();
            assertTrue(manager.getProblems().isEmpty());
            assertTrue(JsonParser.parseString(Files.readString(file())).isJsonObject());
        }
    }

    @Test
    void strictTypesRejectLossyNumbersAndUnavailableEnumModes() throws IOException {
        for (String number : List.of("1.5", "2147483648", "\"9\"", "null", "[]")) {
            Files.writeString(file(), "{\"number\":{\"value\":" + number
                    + "},\"mode\":{\"value\":\"DISALLOWED\"},\"text\":{\"value\":\"valid\"}}");
            Settings settings = new Settings();
            ConfigManager manager = manager(settings);
            manager.load();
            assertEquals(7, settings.number.getValue());
            assertEquals(Choice.FIRST, settings.mode.getValue());
            assertEquals("valid", settings.text.getValue());
            assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("Settings.number")));
            assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("Settings.mode")));
        }
    }

    @Test
    void malformedWrappersAndUnknownSettingsAreReportedAndRecoverable() throws IOException {
        String original = "{\"number\":42,\"enabled\":{},\"removedSetting\":{\"value\":1}}";
        Files.writeString(file(), original);
        Settings settings = new Settings();
        ConfigManager manager = manager(settings);
        manager.load();
        assertEquals(7, settings.number.getValue());
        assertTrue(settings.enabled.getValue());
        assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("removedSetting")));
        assertEquals(original, Files.readString(recoveries().getFirst()));
    }

    @Test
    void meaningfulValuesRoundTripWithoutReflectingSwingInternals() throws IOException {
        Settings settings = new Settings();
        settings.number.setValue(-123);
        settings.enabled.setValue(false);
        settings.text.setValue("Unicode \u4e2d\u6587, newlines\n and <xml>");
        settings.optional.setValue("optional value");
        settings.mode.setValue(Choice.SECOND);
        settings.key.setValue(KeyStroke.getKeyStroke(KeyEvent.VK_F5, InputEvent.SHIFT_DOWN_MASK, true));
        settings.recent.setValue(Set.of("z.jar", "a.jar"));
        settings.nested.getValue().number.setValue(987);
        ConfigManager manager = manager(settings);
        manager.save();
        assertTrue(manager.getProblems().isEmpty());
        assertTrue(recoveries().isEmpty());
        Settings loaded = new Settings();
        ConfigManager reader = manager(loaded);
        reader.load();
        assertTrue(reader.getProblems().isEmpty());
        assertEquals(-123, loaded.number.getValue());
        assertFalse(loaded.enabled.getValue());
        assertEquals(settings.text.getValue(), loaded.text.getValue());
        assertEquals("optional value", loaded.optional.getValue());
        assertEquals(Choice.SECOND, loaded.mode.getValue());
        assertEquals(settings.key.getValue(), loaded.key.getValue());
        assertEquals(Set.of("a.jar", "z.jar"), loaded.recent.getValue());
        assertEquals(987, loaded.nested.getValue().number.getValue());
        String firstSave = Files.readString(file());
        reader.save();
        assertEquals(firstSave, Files.readString(file()));
        try (Stream<Path> paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void nullableStringsAndTypedKeyBindingsRoundTrip() {
        Settings settings = new Settings();
        settings.key.setValue(KeyStroke.getKeyStroke('x'));
        ConfigManager manager = manager(settings);
        manager.save();
        assertTrue(manager.getProblems().isEmpty());
        Settings loaded = new Settings();
        ConfigManager reader = manager(loaded);
        reader.load();
        assertTrue(reader.getProblems().isEmpty());
        assertNull(loaded.optional.getValue());
        assertEquals(KeyStroke.getKeyStroke('x'), loaded.key.getValue());
    }

    @Test
    void legacyKeyObjectIsValidatedAndStillSupported() throws IOException {
        Files.writeString(file(), """
                {"key":{"value":{"keyCode":65,"modifiers":130,"onKeyRelease":false}}}
                """);
        Settings settings = new Settings();
        ConfigManager manager = manager(settings);
        manager.load();
        assertTrue(manager.getProblems().isEmpty());
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), settings.key.getValue());
        Files.writeString(file(), """
                {"key":{"value":{"keyCode":65,"modifiers":{},"onKeyRelease":false}},"number":{"value":99}}
                """);
        manager.load();
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), settings.key.getValue());
        assertEquals(99, settings.number.getValue());
        assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("Settings.key")));
    }

    @Test
    void encodingFailureLeavesExistingFileExactlyIntact() throws IOException {
        Settings settings = new Settings();
        ConfigManager manager = manager(settings);
        manager.save();
        byte[] original = Files.readAllBytes(file());
        settings.key.setValue(null);
        manager.save();
        assertFalse(manager.getProblems().isEmpty());
        assertArrayEquals(original, Files.readAllBytes(file()));
        try (Stream<Path> paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void saveBeforeLoadPreservesAnExistingUninspectedFile() throws IOException {
        String original = "damaged original";
        Files.writeString(file(), original);
        ConfigManager manager = manager(new Settings());
        manager.save();
        assertEquals(original, Files.readString(recoveries().getFirst()));
        assertTrue(JsonParser.parseString(Files.readString(file())).isJsonObject());
    }

    @Test
    void failedRecoveryBlocksSaveAndPreservesExistingDirectoryContents() throws IOException {
        Files.createDirectory(file());
        Path original = file().resolve("recoverable-data.txt");
        Files.writeString(original, "important");
        ConfigManager manager = manager(new Settings());
        manager.load();
        assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("saving it is disabled")));
        manager.save();
        assertTrue(manager.getProblems().stream().anyMatch(problem -> problem.contains("Original could not be preserved")));
        assertEquals("important", Files.readString(original));
        assertTrue(recoveries().isEmpty());
    }

    @Test
    void configurationMapsCannotBeMutatedAndInheritedSettingsAreIncluded() {
        class Derived extends Settings {
            Derived() { postInit(); }
        }
        Settings settings = new Derived();
        ConfigManager manager = manager(settings);
        assertSame(settings.number, settings.getConfigMap().get("number"));
        assertThrows(UnsupportedOperationException.class, () -> settings.getConfigMap().clear());
        assertThrows(UnsupportedOperationException.class, () -> manager.getConfig().clear());
        assertEquals(8, settings.getConfigMap().size());
        assertEquals("7", manager.getConfigMapStrings(settings).get("number"));
    }

    @Test
    void mutableRecentSetRetainsCallerSemanticsWithoutLeakingConstructorInput() {
        Set<String> input = new HashSet<>(Set.of("first.jar"));
        StringSetValue value = new StringSetValue("Recent", input, "");
        input.add("external.jar");
        assertEquals(Set.of("first.jar"), value.getValue());
        Iterator<String> iterator = value.getValue().iterator();
        value.getValue().add("next.jar");
        assertEquals("first.jar", iterator.next());
        assertFalse(iterator.hasNext());
        value.getValue().remove("first.jar");
        assertEquals(Set.of("next.jar"), value.getValue());
    }

    @Test
    void configListenersAreCopiedAndAllAreInvokedOnFailure() {
        AtomicInteger called = new AtomicInteger();
        Set<Consumer<Config>> listeners = new LinkedHashSet<>();
        RuntimeException first = new IllegalStateException("first");
        listeners.add(config -> { throw first; });
        listeners.add(config -> called.incrementAndGet());
        Config config = new Config("Listeners", listeners);
        listeners.clear();
        assertSame(first, assertThrows(IllegalStateException.class, config::update));
        assertEquals(1, called.get());
    }

    @Test
    void valueListenerRegistrationDuringNotificationAffectsTheNextTransition() {
        IntegerValue value = new IntegerValue("Number", 1, "");
        List<String> transitions = new ArrayList<>();
        value.addListener((instance, oldValue, newValue) -> {
            transitions.add(oldValue + "->" + newValue);
            instance.addListener((other, old, next) -> transitions.add("later:" + old + "->" + next));
        });
        value.setValue(2);
        assertEquals(List.of("1->2"), transitions);
        value.setValue(3);
        assertEquals(List.of("1->2", "2->3", "later:2->3"), transitions);
    }

    @Test
    void valueListenerFailureDoesNotSkipOtherListenersOrUndoCommittedValue() {
        IntegerValue value = new IntegerValue("Number", 1, "");
        AtomicInteger called = new AtomicInteger();
        RuntimeException first = new IllegalStateException("listener failed");
        value.addListener((instance, oldValue, newValue) -> { throw first; });
        value.addListener((instance, oldValue, newValue) -> called.incrementAndGet());
        assertSame(first, assertThrows(IllegalStateException.class, () -> value.setValue(2)));
        assertEquals(2, value.getValue());
        assertEquals(1, called.get());
    }
}
