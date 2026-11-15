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

package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;

import java.util.*;

/**
 * @author Enaium
 */
public class Jar {
    // Internal names index base classes. Versioned class entries remain resources.
    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final Collection<ClassNode> classView = Collections.unmodifiableCollection(classes.values());
    private final Map<String, byte[]> resourceView = Collections.unmodifiableMap(resources);
    private long structuralRevision;

    /** Names and class wrapper identities only; never a content or commit version. */
    public long getStructuralRevision() {
        return structuralRevision;
    }

    /**
     * @param classNode the classNode
     */
    public void putClassNode(ClassNode classNode){
        Objects.requireNonNull(classNode, "classNode");
        String name = Objects.requireNonNull(classNode.getInternalName(), "internalName");
        if (classes.put(name, classNode) != classNode) structuralRevision++;
    }

    public ClassNode removeClass(String internalName) {
        ClassNode removed = classes.remove(internalName);
        if (removed != null) structuralRevision++;
        return removed;
    }

    /**
     * @param internalName the internal name, e.g. "java/lang/Object"
     * @return the classNode
     */
    public ClassNode getClassNode(String internalName){
        return classes.get(internalName);
    }

    /**
     * @param internalName the internal name, e.g. "java/lang/Object"
     * @return true if has
     */
    public boolean hasClass(String internalName) {
        return classes.containsKey(internalName);
    }

    /**
     * @return collection of ClassNodes
     */
    public Collection<ClassNode> getClasses(){
        return classView;
    }

    public int getClassSize() {
        return this.classes.size();
    }

    /**
     * @return a read-only resource index, path -> bytes. Byte arrays remain editable.
     */
    public Map<String,byte[]> getResources() {
        return resourceView;
    }

    /**
     * @param path the path, e.g. "assets/lang/en_us.lang"
     * @param value the bytes, not null
     */
    public void putResource(String path, byte[] value) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        if (resources.put(path, value) == null) structuralRevision++;
    }

    public void putAllResource(Map<String, byte[]> map) {
        map.forEach((path, value) -> {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(value, "value");
        });
        map.forEach(this::putResource);
    }

    public byte[] removeResource(String path) {
        byte[] removed = resources.remove(path);
        if (removed != null) structuralRevision++;
        return removed;
    }

    /**
     * @param path the path, e.g. "assets/lang/en_us.lang"
     * @return the bytes, might null
     */
    public byte[] getResource(String path) {
        return resources.get(path);
    }

    public int getResourceSize(){
        return resources.size();
    }

    /** Reindexes renamed classes, leaving the old index intact if names collide. */
    public void refresh(){
        boolean renamed = false;
        for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
            String name = Objects.requireNonNull(entry.getValue().getInternalName(), "internalName");
            if (!entry.getKey().equals(name)) renamed = true;
        }
        if (!renamed) return;
        Map<String, ClassNode> refreshed = new LinkedHashMap<>(classes.size());
        for (ClassNode classNode : classes.values()) {
            String name = Objects.requireNonNull(classNode.getInternalName(), "internalName");
            if (refreshed.putIfAbsent(name, classNode) != null) {
                throw new IllegalStateException("Classes have the same internal name: " + name);
            }
        }
        classes.clear();
        classes.putAll(refreshed);
        structuralRevision++;
    }

    public Jar copy() {
        Jar jar = new Jar();
        for (Map.Entry<String, ClassNode> entry : classes.entrySet()) {
            jar.classes.put(entry.getKey(), entry.getValue().copy());
        }
        for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
            jar.putResource(entry.getKey(), entry.getValue().clone());
        }
        return jar;
    }
}
