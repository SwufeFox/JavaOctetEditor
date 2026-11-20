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

package cn.enaium.joe.gui.panel;

import cn.enaium.joe.util.LangUtil;
import cn.enaium.joe.util.StringUtil;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses field and method metadata form text. Callers validate every edited
 * value here before assigning anything to the live member node.
 *
 * @author Enaium
 */
public final class MemberMetadata {
    private MemberMetadata() {
    }

    public static String fieldDescriptor(String descriptor) {
        String value = descriptor == null ? "" : descriptor.trim();
        try {
            Type type = Type.getType(value);
            if (type.getSort() == Type.METHOD || type.getSort() == Type.VOID
                    || !canonicalDescriptor(type).equals(value) || emptyObjectName(type)) {
                throw invalidField(value);
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            throw invalidField(value, malformed);
        }
    }

    public static String methodDescriptor(String descriptor) {
        String value = descriptor == null ? "" : descriptor.trim();
        try {
            Type type = Type.getMethodType(value);
            if (!Type.getMethodDescriptor(type.getReturnType(), type.getArgumentTypes()).equals(value)
                    || emptyObjectName(type.getReturnType()) || emptyObjectArgument(type)) {
                throw invalidMethod(value);
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            throw invalidMethod(value, malformed);
        }
    }

    private static IllegalArgumentException invalidField(String descriptor) {
        return new IllegalArgumentException(LangUtil.i18n("class.info.invalidDescriptor", descriptor));
    }

    private static IllegalArgumentException invalidField(String descriptor, Throwable cause) {
        return new IllegalArgumentException(LangUtil.i18n("class.info.invalidDescriptor", descriptor), cause);
    }

    private static IllegalArgumentException invalidMethod(String descriptor) {
        return new IllegalArgumentException(LangUtil.i18n("class.info.invalidMethodDescriptor", descriptor));
    }

    private static IllegalArgumentException invalidMethod(String descriptor, Throwable cause) {
        return new IllegalArgumentException(LangUtil.i18n("class.info.invalidMethodDescriptor", descriptor), cause);
    }

    private static String canonicalDescriptor(Type type) {
        if (type.getSort() == Type.ARRAY) {
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < type.getDimensions(); index++) builder.append('[');
            return builder.append(canonicalDescriptor(type.getElementType())).toString();
        }
        if (type.getSort() == Type.OBJECT) return "L" + type.getInternalName() + ";";
        return type.getDescriptor();
    }

    private static boolean emptyObjectArgument(Type methodType) {
        for (Type argument : methodType.getArgumentTypes()) {
            if (emptyObjectName(argument)) return true;
        }
        return false;
    }

    private static boolean emptyObjectName(Type type) {
        if (type.getSort() == Type.OBJECT) return type.getInternalName().isEmpty();
        if (type.getSort() == Type.ARRAY) return emptyObjectName(type.getElementType());
        return false;
    }

    public static int access(String text) {
        try {
            return Integer.parseInt(text == null ? "" : text.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(LangUtil.i18n("class.info.invalidAccess"));
        }
    }

    public static String nullableText(String text) {
        return StringUtil.isBlank(text) ? null : text;
    }

    public static List<String> exceptions(String text) {
        List<String> values = new ArrayList<>();
        if (text == null) return values;
        for (String value : text.split(";", -1)) {
            String entry = value.trim();
            if (!entry.isEmpty()) values.add(entry);
        }
        return values;
    }

    public static String displayList(List<String> values) {
        if (values == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) builder.append(';');
            String value = values.get(index);
            builder.append(value == null ? "" : value);
        }
        return builder.toString();
    }
}
