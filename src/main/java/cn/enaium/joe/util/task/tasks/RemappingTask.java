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

package cn.enaium.joe.util.task.tasks;

import cn.enaium.joe.annotation.Indeterminate;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.mapping.Mapping;
import cn.enaium.joe.util.mapping.MappingParser;
import cn.enaium.joe.util.task.AbstractTask;
import net.fabricmc.mappingio.format.MappingFormat;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author Enaium
 * @since 1.0.0
 */
@Indeterminate
public class RemappingTask extends AbstractTask<cn.enaium.joe.util.task.ArchiveChange> {


    public final Map<String, Set<String>> superHashMap = new HashMap<>();

    private final File mapping;
    private final MappingFormat mappingFormat;
    private final cn.enaium.joe.jar.ArchiveSnapshot baseline;

    public RemappingTask(Jar archive, File mapping, MappingFormat mappingFormat) {
        super("Remapping");
        this.mapping = mapping;
        this.mappingFormat = mappingFormat;
        this.baseline = cn.enaium.joe.jar.ArchiveSnapshot.capture(Objects.requireNonNull(archive));
    }

    @Override
    public cn.enaium.joe.util.task.ArchiveChange get() {


        try {
            checkCancelled();
            Mapping read = MappingParser.read(mapping.toPath(), mappingFormat);
            Jar oldJar = baseline.toJar();
            Jar jar = new Jar();
            jar.putAllResource(oldJar.getResources());

            oldJar.getClasses().forEach(this::analyze);
            Map<String, Set<String>> ancestors = new HashMap<>();
            for (String name : superHashMap.keySet()) {
                Set<String> parents = new LinkedHashSet<>();
                Set<String> visited = new HashSet<>();
                ArrayDeque<String> pending = new ArrayDeque<>(superHashMap.get(name));
                visited.add(name);
                while (!pending.isEmpty()) {
                    String parent = pending.removeFirst();
                    if (!visited.add(parent)) continue;
                    parents.add(parent);
                    pending.addAll(superHashMap.getOrDefault(parent, Set.of()));
                }
                ancestors.put(name, parents);
            }
            int completed = 0;

            for (cn.enaium.joe.util.classes.ClassNode stringClassNodeEntry : oldJar.getClasses()) {
                setProgress(completed++ * 100 / Math.max(1, oldJar.getClassSize()));
                ClassNode classNode = new ClassNode();
                ClassRemapper classRemapper = new ClassRemapper(classNode, new SimpleRemapper(read.MAP) {
                    @Override
                    public String mapFieldName(String owner, String name, String descriptor) {
                        String remappedName = map(owner + '.' + name);
                        if (remappedName == null) {
                            for (String parent : ancestors.getOrDefault(owner, Set.of())) {
                                String inherited = map(parent + '.' + name);
                                if (inherited != null) return inherited;
                            }
                        }

                        return remappedName == null ? name : remappedName;
                    }

                    @Override
                    public String mapMethodName(String owner, String name, String descriptor) {
                        String remappedName = map(owner + '.' + name + descriptor);
                        if (remappedName == null) {
                            for (String parent : ancestors.getOrDefault(owner, Set.of())) {
                                String inherited = map(parent + '.' + name + descriptor);
                                if (inherited != null) return inherited;
                            }
                        }
                        return remappedName == null ? name : remappedName;
                    }
                });
                stringClassNodeEntry.analyzeVisitor(classRemapper);
                var replacement = cn.enaium.joe.util.classes.ClassNode.of(classNode);
                if (jar.hasClass(replacement.getInternalName())) {
                    throw new IllegalArgumentException("Mappings produce duplicate class name: " + replacement.getInternalName());
                }
                jar.putClassNode(replacement);
            }
            setProgress(100);
            return new cn.enaium.joe.util.task.ArchiveChange(baseline, jar);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void analyze(cn.enaium.joe.util.classes.ClassNode classNode) {
        classNode.analyzeVisitor(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                Set<String> strings = new HashSet<>();
                if (superHashMap.containsKey(name)) {
                    if (superName != null) {
                        if (!superHashMap.get(name).contains(superName)) {
                            strings.add(superName);
                        }
                    }

                    if (interfaces != null) {
                        for (String inter : interfaces) {
                            if (!superHashMap.get(name).contains(inter)) {
                                strings.add(inter);
                            }
                        }
                    }
                    superHashMap.get(name).addAll(strings);
                } else {
                    if (superName != null) {
                        strings.add(superName);
                    }

                    if (interfaces != null) {
                        Collections.addAll(strings, interfaces);
                    }
                    superHashMap.put(name, strings);
                }
                super.visit(version, access, name, signature, superName, interfaces);
            }
        });
    }
}
