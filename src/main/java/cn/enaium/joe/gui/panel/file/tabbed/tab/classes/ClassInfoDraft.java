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

package cn.enaium.joe.gui.panel.file.tabbed.tab.classes;

import cn.enaium.joe.util.StringUtil;
import cn.enaium.joe.util.classes.ClassNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validates class metadata form text and stages changes on a detached copy.
 * Validation only runs for fields whose text differs from the refreshed baseline,
 * so untouched, unusual bytecode is always preserved as-is.
 *
 * @author Enaium
 */
final class ClassInfoDraft {
    private ClassInfoDraft() {
    }

    record Values(String name, String sourceFile, String debugFile, String access, String version,
                  String signature, String superName, String interfaces, String outerClass,
                  String outerMethod, String outerMethodDescription) {
    }

    static ClassNode stage(ClassNode live, Values current, Values baseline) {
        ClassNode staged = live.copy();
        org.objectweb.asm.tree.ClassNode node = staged.getClassNode();
        if (changed(current.name(), baseline.name())) {
            String name = current.name() == null ? "" : current.name().trim();
            if (!validInternalName(name)) throw new InvalidValueException("class.info.invalidName");
            node.name = name;
        }
        if (changed(current.sourceFile(), baseline.sourceFile())) node.sourceFile = nullable(current.sourceFile());
        if (changed(current.debugFile(), baseline.debugFile())) node.sourceDebug = nullable(current.debugFile());
        if (changed(current.access(), baseline.access())) node.access = parseAccess(current.access());
        if (changed(current.version(), baseline.version())) node.version = parseVersion(current.version(), node.version);
        if (changed(current.signature(), baseline.signature())) node.signature = nullable(current.signature());
        if (changed(current.superName(), baseline.superName())) node.superName = nullable(current.superName());
        if (changed(current.interfaces(), baseline.interfaces())) {
            List<String> interfaces = new ArrayList<>();
            String text = current.interfaces() == null ? "" : current.interfaces();
            for (String value : text.split(";", -1)) {
                String entry = value.trim();
                if (!entry.isEmpty()) interfaces.add(entry);
            }
            node.interfaces = interfaces;
        }
        if (changed(current.outerClass(), baseline.outerClass())) node.outerClass = nullable(current.outerClass());
        if (changed(current.outerMethod(), baseline.outerMethod())) node.outerMethod = nullable(current.outerMethod());
        if (changed(current.outerMethodDescription(), baseline.outerMethodDescription())) {
            node.outerMethodDesc = nullable(current.outerMethodDescription());
        }
        staged.updateBytes();
        return staged;
    }

    static boolean validInternalName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.endsWith("/")) return false;
        boolean hasSegmentCharacter = false;
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == '/') {
                if (!hasSegmentCharacter) return false;
                hasSegmentCharacter = false;
                continue;
            }
            if (character == '.' || character == ';' || character == '[' || character == '\\' || character == 0) {
                return false;
            }
            hasSegmentCharacter = true;
        }
        return hasSegmentCharacter;
    }

    static int parseAccess(String text) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException failure) {
            throw new InvalidValueException("class.info.invalidAccess");
        }
    }

    static int parseVersion(String text, int originalVersion) {
        int major;
        try {
            major = Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException failure) {
            throw new InvalidValueException("class.info.invalidVersion");
        }
        if (major < 45 || major > 0xFFFF) throw new InvalidValueException("class.info.invalidVersion");
        // Preserve preview/minor high bits of the displayed class when only the major version is edited.
        return (originalVersion & 0xFFFF0000) | major;
    }

    private static boolean changed(String current, String baseline) {
        return !Objects.equals(current, baseline);
    }

    private static String nullable(String value) {
        return StringUtil.isBlank(value) ? null : value;
    }

    static final class InvalidValueException extends IllegalArgumentException {
        private final String key;

        private InvalidValueException(String key) {
            super(key);
            this.key = key;
        }

        String key() {
            return key;
        }
    }
}
