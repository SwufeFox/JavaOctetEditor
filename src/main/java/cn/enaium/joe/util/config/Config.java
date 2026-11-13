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

import cn.enaium.joe.util.config.value.Value;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;

/**
 * @author Enaium
 * @since 0.7.0
 */
public class Config {
    private final String name;

    private transient final List<Consumer<Config>> listeners;
    private transient Map<String, Value<?>> values = Collections.emptyMap();

    public Config(String name) {
        this(name, Collections.emptySet());
    }

    public Config(String name, Set<Consumer<Config>> listeners ) {
        this.name = name;
        this.listeners = List.copyOf(listeners);
    }

    // Must call this in the end of constructor
    protected void postInit(){
        Map<String, Value<?>> map = new TreeMap<>();
        for (Class<?> type = getClass(); type != Config.class && type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Value.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Value<?> value = (Value<?>) field.get(this);
                    if (value != null) {
                        map.putIfAbsent(field.getName(), value);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Could not access setting " + field.getName(), e);
                }
            }
        }
        this.values = Collections.unmodifiableMap(map);
    }

    public Map<String, Value<?>> getConfigMap(){
        return values;
    }

    public void update(){
        RuntimeException failure = null;
        for (Consumer<Config> consumer : listeners) {
            try {
                consumer.accept(this);
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    public String getName() {
        return name;
    }

}
