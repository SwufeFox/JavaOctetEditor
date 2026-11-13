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

package cn.enaium.joe.util.config.value;

import cn.enaium.joe.util.config.util.ConfigValueListener;
import com.google.gson.JsonElement;

import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * @author Enaium
 * @since 0.7.0
 */
public abstract class Value<T> {
    private transient final Type type;
    private final String name;
    private volatile T value;
    private final String description;
    private transient final Set<ConfigValueListener<T>> listeners = new CopyOnWriteArraySet<>();

    public Value(Type type, String name, T value, String description) {
        this.type = type;
        this.name = name;
        this.value = value;
        this.description = description;
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        T oldValue = this.value;
        this.value = value;
        RuntimeException failure = null;
        for (ConfigValueListener<T> listener : listeners) {
            try {
                listener.update(this, oldValue, value);
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    public abstract void decode(JsonElement jsonElement);

    public void addListener(ConfigValueListener<T> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    protected static void requirePrimitive(JsonElement element, String kind) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Expected " + kind);
        }
        boolean valid = switch (kind) {
            case "string" -> element.getAsJsonPrimitive().isString();
            case "boolean" -> element.getAsJsonPrimitive().isBoolean();
            case "integer" -> element.getAsJsonPrimitive().isNumber();
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Expected " + kind);
    }

    protected static int integer(JsonElement element) {
        requirePrimitive(element, "integer");
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Expected a 32-bit integer", e);
        }
    }
}
