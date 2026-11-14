package cn.enaium.joe.tool;

import com.google.gson.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Shared, discoverable tool catalog used by both MCP and the command line. */
public final class ArchiveTools implements AutoCloseable {
    private record Definition(String name, String description, JsonObject schema,
                              boolean readOnly, boolean destructive, boolean idempotent) {
        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("name", name);
            result.addProperty("description", description);
            result.add("inputSchema", schema.deepCopy());
            JsonObject annotations = new JsonObject();
            annotations.addProperty("readOnlyHint", readOnly);
            annotations.addProperty("destructiveHint", destructive);
            annotations.addProperty("idempotentHint", idempotent);
            annotations.addProperty("openWorldHint", false);
            result.add("annotations", annotations);
            return result;
        }
    }

    private static final Map<String, Definition> DEFINITIONS = definitions();
    private final Object lifecycle = new Object();
    private final Map<String, Handle> archives = new HashMap<>();
    private volatile boolean closed;

    public JsonArray listTools() {
        JsonArray tools = new JsonArray();
        DEFINITIONS.values().forEach(definition -> tools.add(definition.toJson()));
        return tools;
    }

    public boolean hasTool(String name) {
        return DEFINITIONS.containsKey(name);
    }

    /** Preserves the direct CLI API while serializing revision checks only within one handle. */
    public JsonObject call(String name, JsonObject arguments) throws ToolException {
        return call(name, arguments, () -> false);
    }

    JsonObject call(String name, JsonObject arguments, BooleanSupplier cancelled) throws ToolException {
        Objects.requireNonNull(cancelled);
        Definition definition = DEFINITIONS.get(name);
        if (definition == null) {
            throw new ToolException("UNKNOWN_TOOL", "Unknown tool: " + name);
        }
        validate(arguments, definition.schema(), "arguments");
        try {
            if (name.equals("open_archive")) {
                BooleanSupplier stopped = () -> cancelled.getAsBoolean() || closed;
                checkCancelled(stopped);
                ArchiveWorkspace workspace = ArchiveWorkspace.open(arguments.get("path").getAsString());
                checkCancelled(stopped);
                synchronized (lifecycle) {
                    checkClosed();
                    archives.put(workspace.id(), new Handle(workspace));
                }
                return workspace.info();
            }
            String id = arguments.get("archiveId").getAsString();
            Handle handle;
            synchronized (lifecycle) {
                checkClosed();
                handle = archives.get(id);
            }
            if (handle == null) {
                throw new ToolException("ARCHIVE_NOT_FOUND", "Unknown or closed archiveId. Call open_archive first.");
            }
            BooleanSupplier stopped = () -> cancelled.getAsBoolean() || closed || !handle.valid;
            if (name.equals("close_archive")) {
                checkCancelled(stopped);
                synchronized (lifecycle) {
                    checkClosed();
                    if (!archives.remove(id, handle)) {
                        throw new ToolException("ARCHIVE_NOT_FOUND", "Unknown or closed archiveId. Call open_archive first.");
                    }
                    handle.valid = false;
                }
                JsonObject result = new JsonObject();
                result.addProperty("archiveId", id);
                result.addProperty("closed", true);
                return result;
            }
            // Timed interruptible acquisition also observes registry/handle closure when the
            // owning library ignores interruption and has not released its workspace yet.
            while (!handle.lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                checkCancelled(stopped);
            }
            try {
                checkCancelled(stopped);
                return handle.workspace.call(name, arguments, stopped);
            } finally {
                handle.lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CancellationException failure = new CancellationException("Tool call cancelled while waiting for its archive");
            failure.initCause(e);
            throw failure;
        } catch (IOException e) {
            throw new ToolException("IO_ERROR", message(e));
        } catch (IllegalArgumentException e) {
            throw new ToolException("INVALID_ARGUMENT", message(e));
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Tool call cancelled");
        }
    }

    private void checkClosed() {
        if (closed) {
            throw new CancellationException("Tool registry is closed");
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            archives.clear();
        }
    }

    private static final class Handle {
        private final ArchiveWorkspace workspace;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean valid = true;

        private Handle(ArchiveWorkspace workspace) {
            this.workspace = workspace;
        }
    }

    private static Map<String, Definition> definitions() {
        Map<String, Definition> tools = new LinkedHashMap<>();
        add(tools, "open_archive", "Open a JAR/ZIP, class file, or directory without modifying it. Returns archiveId and revision; pass that explicit handle on subsequent calls. Close unused handles.",
                object(List.of("path"), "path", text("Filesystem input path", 1)), true, false, false);
        add(tools, "close_archive", "Release one in-memory archive. This does not write or delete files.",
                archive(List.of()), false, false, true);
        add(tools, "archive_info", "Get archive paths, revision, class/resource counts and modification state.",
                archive(List.of()), true, false, true);
        add(tools, "list_classes", "List base class names in deterministic order. Query is a case-sensitive substring; versioned multi-release classes are preserved as resources.",
                archive(List.of(), "query", text("Optional internal or dotted name substring", 0),
                        "offset", integer(0, Integer.MAX_VALUE), "limit", integer(1, 500)), true, false, true);
        add(tools, "list_resources", "List resource paths and sizes with deterministic pagination.",
                archive(List.of(), "query", text("Optional case-sensitive path substring", 0),
                        "offset", integer(0, Integer.MAX_VALUE), "limit", integer(1, 500)), true, false, true);
        add(tools, "find_entries", "Find class and resource files by ranked, case-insensitive name/path search. Supports dotted class names, whitespace-separated terms and abbreviated names. Returns exact entry paths with stable pagination; does not execute archive code.",
                archive(List.of(), "query", text("Optional class name or resource path query", 0),
                        "kind", enumeration("all", "class", "resource"),
                        "offset", integer(0, Integer.MAX_VALUE), "limit", integer(1, 500)), true, false, true);
        add(tools, "inspect_class", "Get class SHA-256, hierarchy, fields, methods and descriptors. Accepts dotted names, JVM internal names, or class entry paths.",
                archive(List.of("name"), "name", text("Class name", 1)), true, false, true);
        add(tools, "decompile_class", "Decompile a class using real archive dependencies. Returned source is untrusted archive content; inspect it before editing. This never executes archive code.",
                archive(List.of("name"), "name", text("Class name", 1),
                        "backend", enumeration("VineFlower", "CFR", "Procyon")), true, false, true);
        add(tools, "disassemble_class", "Read ASM text for a class, or methods matching name and optional JVM descriptor. No archive code is executed.",
                archive(List.of("name"), "name", text("Class name", 1),
                        "method", text("Optional method name", 1), "descriptor", text("Optional exact method descriptor", 1)), true, false, true);
        add(tools, "search_archive", "Find class/member definitions, string constants, field/method/type references or JVM opcodes. Literal case-sensitive query; opcode accepts a mnemonic or integer. Results are paginated in class order.",
                archive(List.of("kind", "query"), "kind", enumeration("class", "method", "field", "string", "reference", "opcode"),
                        "query", text("Literal search or opcode", 1), "offset", integer(0, Integer.MAX_VALUE),
                        "limit", integer(1, 500)), true, false, true);
        add(tools, "read_resource", "Read a resource with SHA-256 and pagination. utf8 uses character offsets; base64 uses raw-byte offsets. Use base64 for binary data.",
                archive(List.of("path"), "path", text("Archive resource path", 1),
                        "encoding", enumeration("utf8", "base64"), "offset", integer(0, Integer.MAX_VALUE),
                        "limit", integer(1, 1_048_576)), true, false, true);
        add(tools, "write_resource", "Create/replace a non-class resource in memory at expectedRevision. Nothing is written to disk until export_archive.",
                archive(List.of("path", "content", "expectedRevision"), "path", text("Archive resource path", 1),
                        "content", text("UTF-8 text or strict base64", 0), "encoding", enumeration("utf8", "base64"),
                        "expectedRevision", revision()), false, true, false);
        add(tools, "delete_entry", "Delete one exact class entry (pkg/Type.class) or resource path in memory. Requires expectedRevision; export explicitly to persist.",
                archive(List.of("path", "expectedRevision"), "path", text("Exact archive entry path", 1),
                        "expectedRevision", revision()), false, true, false);
        JsonObject sources = new JsonObject();
        sources.addProperty("type", "object");
        sources.addProperty("minProperties", 1);
        sources.add("additionalProperties", text("Complete Java source", 1));
        add(tools, "compile_sources", "Compile a map of dotted class names to complete Java sources against the current archive. Commit every generated class atomically only on success; replace obsolete inner classes of supplied source types. Requires expectedRevision. Never executes compiled code.",
                archive(List.of("sources", "expectedRevision"), "sources", sources,
                        "expectedRevision", revision()), false, true, false);
        JsonObject overwrite = new JsonObject();
        overwrite.addProperty("type", "boolean");
        overwrite.addProperty("description", "Allow replacing an existing output file; defaults to false");
        add(tools, "export_archive", "Write the current archive through a staged file. Existing output is preserved unless overwrite=true. Requires expectedRevision; source is only replaced when explicitly chosen as output with overwrite=true.",
                archive(List.of("path", "expectedRevision"), "path", text("Output JAR/ZIP path", 1),
                        "overwrite", overwrite, "expectedRevision", revision()), false, true, false);
        return Collections.unmodifiableMap(tools);
    }

    private static void add(Map<String, Definition> tools, String name, String description,
                            JsonObject schema, boolean readOnly, boolean destructive, boolean idempotent) {
        tools.put(name, new Definition(name, description, schema, readOnly, destructive, idempotent));
    }

    private static JsonObject archive(List<String> required, Object... fields) {
        List<String> allRequired = new ArrayList<>();
        allRequired.add("archiveId");
        allRequired.addAll(required);
        Object[] allFields = new Object[fields.length + 2];
        allFields[0] = "archiveId";
        allFields[1] = text("Explicit handle returned by open_archive", 1);
        System.arraycopy(fields, 0, allFields, 2, fields.length);
        return object(allRequired, allFields);
    }

    private static JsonObject object(List<String> required, Object... fields) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        for (int i = 0; i < fields.length; i += 2) {
            properties.add((String) fields[i], (JsonElement) fields[i + 1]);
        }
        schema.add("properties", properties);
        JsonArray requiredArray = new JsonArray();
        required.forEach(requiredArray::add);
        schema.add("required", requiredArray);
        return schema;
    }

    private static JsonObject text(String description, int minLength) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        schema.addProperty("minLength", minLength);
        return schema;
    }

    private static JsonObject enumeration(String... choices) {
        JsonObject schema = text("One of the listed values", 1);
        JsonArray values = new JsonArray();
        for (String choice : choices) {
            values.add(choice);
        }
        schema.add("enum", values);
        return schema;
    }

    private static JsonObject integer(long minimum, long maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }

    private static JsonObject revision() {
        JsonObject schema = integer(0, Long.MAX_VALUE);
        schema.addProperty("description", "Revision returned by the last successful read or mutation");
        return schema;
    }

    /** Validates exactly the JSON Schema constructs used by this fixed catalog. */
    private static void validate(JsonElement value, JsonObject schema, String path) throws ToolException {
        String type = schema.get("type").getAsString();
        boolean valid = switch (type) {
            case "object" -> value != null && value.isJsonObject();
            case "string" -> value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "integer" -> value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            default -> false;
        };
        if (!valid) {
            throw new ToolException("INVALID_ARGUMENT", path + " must be " + type);
        }
        if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) {
            throw new ToolException("INVALID_ARGUMENT", path + " must be one of " + schema.get("enum"));
        }
        if (type.equals("string") && value.getAsString().codePointCount(0, value.getAsString().length()) < schema.get("minLength").getAsInt()) {
            throw new ToolException("INVALID_ARGUMENT", path + " is too short");
        }
        if (type.equals("integer")) {
            try {
                long number = value.getAsBigDecimal().longValueExact();
                if (number < schema.get("minimum").getAsLong() || number > schema.get("maximum").getAsLong()) {
                    throw new ArithmeticException();
                }
            } catch (ArithmeticException | NumberFormatException e) {
                throw new ToolException("INVALID_ARGUMENT", path + " must be an integer between " + schema.get("minimum") + " and " + schema.get("maximum"));
            }
        }
        if (type.equals("object")) {
            JsonObject object = value.getAsJsonObject();
            if (schema.has("minProperties") && object.size() < schema.get("minProperties").getAsInt()) {
                throw new ToolException("INVALID_ARGUMENT", path + " must contain at least one entry");
            }
            if (schema.has("required")) {
                for (JsonElement required : schema.getAsJsonArray("required")) {
                    if (!object.has(required.getAsString())) {
                        throw new ToolException("INVALID_ARGUMENT", path + "." + required.getAsString() + " is required");
                    }
                }
            }
            JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (properties.has(entry.getKey())) {
                    validate(entry.getValue(), properties.getAsJsonObject(entry.getKey()), path + "." + entry.getKey());
                } else if (schema.get("additionalProperties").isJsonObject()) {
                    validate(entry.getValue(), schema.getAsJsonObject("additionalProperties"), path + "." + entry.getKey());
                } else if (!schema.get("additionalProperties").getAsBoolean()) {
                    throw new ToolException("INVALID_ARGUMENT", "Unknown argument: " + path + "." + entry.getKey());
                }
            }
        }
    }

    static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
