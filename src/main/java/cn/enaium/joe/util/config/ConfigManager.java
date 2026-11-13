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

package cn.enaium.joe.util.config;

import cn.enaium.joe.util.Util;
import cn.enaium.joe.util.config.value.*;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import org.tinylog.Logger;

import java.awt.Component;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Enaium
 * @since 0.7.0
 */
public class ConfigManager {
    public final HashMap<Class<? extends Value<?>>, Function<? extends Value<?>, Component>> GUI_FACTORY = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .serializeNulls().setStrictness(Strictness.STRICT).create();

    private final Map<Class<? extends Config>, Config> configMap = new LinkedHashMap<>();
    private final Path directory;
    private final Consumer<String> errorHandler;
    private final List<String> problems = new ArrayList<>();
    private final Set<Path> inspectedFiles = new HashSet<>();
    private final Set<Path> blockedFiles = new HashSet<>();

    public ConfigManager() {
        this(Path.of(System.getProperty("user.dir")));
    }

    public ConfigManager(Path directory) {
        this(directory, message -> Logger.warn(message));
    }

    public ConfigManager(Path directory, Consumer<String> errorHandler) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.errorHandler = Objects.requireNonNull(errorHandler);
        addGuiFactory(IntegerValue.class, IntegerValue::createGui);
        addGuiFactory(StringValue.class, StringValue::createGui);
        addGuiFactory(EnableValue.class, EnableValue::createGui);
        addGuiFactory(ConfigValue.class, ConfigValue::createGui);
        addGuiFactory(KeyValue.class, KeyValue::createGui);
        addGuiFactory(ModeValue.class, ModeValue::createGui);
    }

    public synchronized List<String> getProblems() {
        return List.copyOf(problems);
    }

    public <T extends Value<?>> void addGuiFactory(Class<T> klass, Function<T, Component> function) {
        GUI_FACTORY.put(klass, function);
    }

    public Component createGuiComponent(Value<?> config) {
        Function<? extends Value<?>, Component> factory = GUI_FACTORY.get(config.getClass());
        if (factory == null) {
            throw new IllegalArgumentException("No settings control for " + config.getClass().getName());
        }
        return factory.apply(Util.cast(config));
    }

    public synchronized <T> T getByClass(Class<T> klass) {
        Config config = configMap.get(klass);
        if (config == null) {
            throw new IllegalArgumentException("No registered configuration for " + klass.getName());
        }
        return klass.cast(config);
    }

    public synchronized <T extends Config> void addByClass(Class<T> config) {
        try {
            addByInstance(config.getConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Could not create configuration " + config.getName(), e);
        }
    }

    public synchronized void addByInstance(Config config) {
        Objects.requireNonNull(config);
        configFile(config);
        configMap.put(config.getClass(), config);
    }

    public synchronized Map<Class<? extends Config>, Config> getConfig() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(configMap));
    }

    public Map<String, String> getConfigMapStrings(Config config) {
        return config.getConfigMap().entrySet().stream()
                .filter(entry -> entry.getValue().getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getValue().toString(),
                        (first, second) -> first, LinkedHashMap::new));
    }

    public Map<String, String> getConfigMapStrings(Class<? extends Config> config) {
        return getConfigMapStrings(getByClass(config));
    }

    public Map<String, Value<?>> getConfigMap(Config config) {
        return config.getConfigMap();
    }

    public Map<String, Value<?>> getConfigMap(Class<? extends Config> config) {
        return getByClass(config).getConfigMap();
    }

    public synchronized void load() {
        problems.clear();
        for (Config config : new ArrayList<>(configMap.values())) {
            Path file = configFile(config);
            List<String> errors = new ArrayList<>();
            boolean readable = false;
            if (Files.exists(file)) {
                try (Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                     JsonReader reader = new JsonReader(input)) {
                    reader.setStrictness(Strictness.STRICT);
                    JsonElement element = GSON.fromJson(reader, JsonElement.class);
                    if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                        throw new JsonParseException("Unexpected trailing JSON content");
                    }
                    readable = true;
                    if (element == null || !element.isJsonObject()) {
                        errors.add("Expected a JSON object");
                    } else {
                        decode(config, element.getAsJsonObject(), config.getName(), errors);
                    }
                } catch (IOException | RuntimeException e) {
                    errors.add(reason(e));
                }
                inspectedFiles.add(file);
                if (!errors.isEmpty()) {
                    preserveOriginal(file);
                } else {
                    blockedFiles.remove(file);
                }
                if (!readable && !Files.isRegularFile(file)) {
                    blockedFiles.add(file);
                }
            } else {
                inspectedFiles.add(file);
            }
            for (String error : errors) {
                report("Could not load setting from " + file + ": " + error
                        + ". Unaffected settings remain available; repair the file or its recovery copy.");
            }
            try {
                config.update();
            } catch (RuntimeException e) {
                report("Could not apply configuration " + file + ": " + reason(e));
            }
        }
    }

    public static void decodeConfig(Config config, JsonObject jsonObject) {
        List<String> errors = new ArrayList<>();
        decode(config, jsonObject, config.getName(), errors);
        config.update();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static void decode(Config config, JsonObject json, String prefix, List<String> errors) {
        Map<String, Value<?>> values = config.getConfigMap();
        for (String key : json.keySet()) {
            if (!key.equals("name") && !values.containsKey(key)) {
                errors.add(prefix + "." + key + ": unrecognized setting");
            }
        }
        for (Map.Entry<String, Value<?>> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!json.has(key)) {
                continue;
            }
            String location = prefix + "." + key;
            try {
                JsonElement wrapper = json.get(key);
                if (!wrapper.isJsonObject() || !wrapper.getAsJsonObject().has("value")) {
                    throw new IllegalArgumentException("Expected an object containing 'value'");
                }
                JsonElement element = wrapper.getAsJsonObject().get("value");
                Value<?> value = entry.getValue();
                if (value instanceof ConfigValue<?> nested) {
                    if (!element.isJsonObject()) {
                        throw new IllegalArgumentException("Expected nested settings object");
                    }
                    decode(nested.getValue(), element.getAsJsonObject(), location, errors);
                    nested.getValue().update();
                } else {
                    value.decode(element);
                }
            } catch (RuntimeException e) {
                errors.add(location + ": " + reason(e));
            }
        }
    }

    public synchronized void save() {
        problems.clear();
        for (Config config : new ArrayList<>(configMap.values())) {
            Path file = configFile(config);
            Path temporary = null;
            try {
                if (blockedFiles.contains(file)) {
                    throw new IOException("Original could not be preserved; repair its permissions or move it before saving");
                }
                // Encode before touching disk; inaccessible JDK fields (e.g. KeyStroke) are never reflected.
                String json = GSON.toJson(encode(config));
                Files.createDirectories(directory);
                if (Files.exists(file) && !inspectedFiles.contains(file) && !preserveOriginal(file)) {
                    throw new IOException("Could not preserve existing configuration before its first save");
                }
                temporary = Files.createTempFile(directory, file.getFileName() + ".", ".tmp");
                try (BufferedWriter output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    output.write(json);
                }
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                // If atomic replacement is unsupported, fail with the original intact.
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                temporary = null;
                inspectedFiles.add(file);
                config.update();
            } catch (IOException | RuntimeException e) {
                report("Could not save configuration " + file + ": " + reason(e));
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException e) {
                        report("Could not remove temporary configuration " + temporary + ": " + reason(e));
                    }
                }
            }
        }
    }

    private static JsonObject encode(Config config) {
        JsonObject json = new JsonObject();
        json.addProperty("name", config.getName());
        for (Map.Entry<String, Value<?>> entry : config.getConfigMap().entrySet()) {
            Value<?> setting = entry.getValue();
            Object value = setting.getValue();
            JsonElement encoded;
            if (setting instanceof ConfigValue<?> nested) {
                encoded = encode(nested.getValue());
            } else if (setting instanceof KeyValue) {
                encoded = new JsonPrimitive(Objects.requireNonNull(value, entry.getKey() + " must have a key binding").toString());
            } else if (setting instanceof StringSetValue strings) {
                JsonArray array = new JsonArray();
                strings.getValue().stream().sorted().forEach(array::add);
                encoded = array;
            } else {
                encoded = GSON.toJsonTree(value);
            }
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("name", setting.getName());
            wrapper.addProperty("description", setting.getDescription());
            wrapper.add("value", encoded);
            json.add(entry.getKey(), wrapper);
        }
        return json;
    }

    private Path configFile(Config config) {
        Path path = directory.resolve(config.getName() + ".json").normalize();
        if (!directory.equals(path.getParent())) {
            throw new IllegalArgumentException("Configuration name must be a file name: " + config.getName());
        }
        return path;
    }

    private boolean preserveOriginal(Path file) {
        Path recovery = null;
        try {
            if (!Files.isRegularFile(file)) {
                throw new IOException("Expected a regular configuration file");
            }
            recovery = Files.createTempFile(directory, file.getFileName() + ".recovery-", ".bak");
            Files.copy(file, recovery, StandardCopyOption.REPLACE_EXISTING);
            blockedFiles.remove(file);
            report("Original configuration preserved at " + recovery);
            return true;
        } catch (IOException e) {
            blockedFiles.add(file);
            report("Could not preserve " + file + "; saving it is disabled: " + reason(e));
            if (recovery != null) {
                try {
                    Files.deleteIfExists(recovery);
                } catch (IOException cleanup) {
                    report("Could not remove incomplete recovery file " + recovery + ": " + reason(cleanup));
                }
            }
            return false;
        }
    }

    private void report(String message) {
        problems.add(message);
        try {
            errorHandler.accept(message);
        } catch (RuntimeException e) {
            Logger.error(e, message);
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
