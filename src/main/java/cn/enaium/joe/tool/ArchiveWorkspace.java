package cn.enaium.joe.tool;

import cn.enaium.joe.jar.ArchiveIO;
import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.jar.ArchiveEntryIndex;
import cn.enaium.joe.jar.ResourceEntryPath;
import cn.enaium.joe.service.DecompileService;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.compiler.CompileError;
import cn.enaium.joe.jar.ArchiveCompilation;
import cn.enaium.joe.jar.ArchiveSnapshot;
import com.google.gson.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** A private archive model. Calls for one handle are serialized by ArchiveTools. */
final class ArchiveWorkspace {
    private final String id = UUID.randomUUID().toString();
    private final Path source;
    private final Jar jar;
    private final Map<String, String> digests = new HashMap<>();
    private List<String> classNames;
    private List<String> resourceNames;
    private ArchiveEntryIndex entryIndex;
    private long entryIndexRevision = -1;
    private Path lastExport;
    private long revision;

    private ArchiveWorkspace(Path source, Jar jar) {
        this.source = source;
        this.jar = jar;
    }

    static ArchiveWorkspace open(String input) throws IOException {
        if (input.isBlank()) {
            throw new IllegalArgumentException("Input path must not be blank");
        }
        Path source = Path.of(input).toRealPath();
        return new ArchiveWorkspace(source, ArchiveIO.read(source));
    }

    String id() {
        return id;
    }

    JsonObject info() {
        JsonObject result = response();
        result.addProperty("sourcePath", source.toString());
        result.addProperty("classCount", jar.getClassSize());
        result.addProperty("resourceCount", jar.getResourceSize());
        result.addProperty("modified", revision != 0);
        if (lastExport != null) {
            result.addProperty("lastExportPath", lastExport.toString());
        }
        return result;
    }

    JsonObject call(String tool, JsonObject args, BooleanSupplier cancelled) throws IOException, ToolException {
        return switch (tool) {
            case "archive_info" -> info();
            case "list_classes" -> listClasses(args);
            case "list_resources" -> listResources(args);
            case "inspect_class" -> inspect(args);
            case "find_entries" -> findEntries(args, cancelled);
            case "decompile_class" -> decompile(args);
            case "disassemble_class" -> disassemble(args);
            case "search_archive" -> search(args);
            case "read_resource" -> readResource(args);
            case "write_resource" -> writeResource(args, cancelled);
            case "delete_entry" -> deleteEntry(args, cancelled);
            case "compile_sources" -> compile(args, cancelled);
            case "export_archive" -> export(args, cancelled);
            default -> throw new ToolException("UNKNOWN_TOOL", "Unknown tool: " + tool);
        };
    }

    private JsonObject response() {
        JsonObject result = new JsonObject();
        result.addProperty("archiveId", id);
        result.addProperty("revision", revision);
        return result;
    }

    private void checkRevision(JsonObject args, BooleanSupplier cancelled) throws ToolException {
        checkCancelled(cancelled);
        long expected = args.get("expectedRevision").getAsLong();
        if (expected != revision) {
            JsonObject details = response();
            details.addProperty("expectedRevision", expected);
            throw new ToolException("REVISION_CONFLICT", "Archive changed. Inspect the current revision before retrying the edit.", details);
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Tool call cancelled");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        checkCancelled();
        if (cancelled.getAsBoolean()) throw new CancellationException("Tool call cancelled");
    }

    private List<String> classes() {
        if (classNames == null) {
            classNames = jar.getClasses().stream().map(ClassNode::getInternalName).sorted().toList();
        }
        return classNames;
    }

    private List<String> resources() {
        if (resourceNames == null) {
            resourceNames = jar.getResources().keySet().stream().sorted().toList();
        }
        return resourceNames;
    }

    private JsonObject listClasses(JsonObject args) {
        String query = text(args, "query", "").replace('.', '/');
        Page page = new Page(args);
        for (String name : classes()) {
            if (name.contains(query) && page.include()) {
                JsonObject item = new JsonObject();
                item.addProperty("name", name);
                item.addProperty("canonicalName", name.replace('/', '.'));
                page.items.add(item);
            }
            if (page.full()) {
                break;
            }
        }
        JsonObject result = response();
        page.finish(result, "classes");
        return result;
    }

    private JsonObject listResources(JsonObject args) {
        String query = text(args, "query", "");
        Page page = new Page(args);
        for (String name : resources()) {
            if (name.contains(query) && page.include()) {
                JsonObject item = new JsonObject();
                item.addProperty("path", name);
                item.addProperty("size", jar.getResource(name).length);
                page.items.add(item);
            }
            if (page.full()) {
                break;
            }
        }
        JsonObject result = response();
        page.finish(result, "resources");
        return result;
    }

    private JsonObject findEntries(JsonObject args, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        if (entryIndex == null || entryIndexRevision != revision) {
            entryIndex = ArchiveEntryIndex.capture(jar);
            entryIndexRevision = revision;
        }
        String kind = text(args, "kind", "all");
        Set<ArchiveEntryIndex.Kind> kinds = switch (kind) {
            case "class" -> Set.of(ArchiveEntryIndex.Kind.CLASS);
            case "resource" -> Set.of(ArchiveEntryIndex.Kind.RESOURCE);
            default -> EnumSet.allOf(ArchiveEntryIndex.Kind.class);
        };
        ArchiveEntryIndex.Page page = entryIndex.search(text(args, "query", ""), kinds,
                number(args, "offset", 0), number(args, "limit", 100));
        checkCancelled(cancelled);
        JsonArray entries = new JsonArray();
        for (ArchiveEntryIndex.Entry entry : page.entries()) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", entry.kind().name().toLowerCase(Locale.ROOT));
            item.addProperty("path", entry.path());
            item.addProperty("displayName", entry.displayName());
            entries.add(item);
        }
        JsonObject result = response();
        result.add("entries", entries);
        result.addProperty("total", page.total());
        result.addProperty("hasMore", page.hasMore());
        if (page.hasMore()) result.addProperty("nextOffset", page.nextOffset());
        return result;
    }

    private ClassNode findClass(JsonObject args) throws ToolException {
        String name = args.get("name").getAsString();
        if (name.endsWith(".class")) {
            name = name.substring(0, name.length() - 6);
        }
        name = name.replace('.', '/');
        ClassNode node = jar.getClassNode(name);
        if (node == null) {
            throw new ToolException("CLASS_NOT_FOUND", "Class not found: " + name);
        }
        return node;
    }

    private JsonObject inspect(JsonObject args) throws ToolException {
        ClassNode wrapper = findClass(args);
        org.objectweb.asm.tree.ClassNode node = wrapper.getNodeInternal();
        JsonObject result = response();
        result.addProperty("name", node.name);
        result.addProperty("canonicalName", node.name.replace('/', '.'));
        result.addProperty("sha256", digest(node.name + ".class", wrapper.getClassBytes()));
        result.addProperty("access", node.access);
        result.addProperty("version", node.version);
        if (node.superName != null) {
            result.addProperty("superName", node.superName);
        }
        result.add("interfaces", strings(node.interfaces));
        JsonArray fields = new JsonArray();
        for (FieldNode field : node.fields) {
            JsonObject item = member(field.name, field.desc, field.access);
            if (field.value != null) {
                item.addProperty("constant", String.valueOf(field.value));
            }
            fields.add(item);
        }
        result.add("fields", fields);
        JsonArray methods = new JsonArray();
        for (MethodNode method : node.methods) {
            JsonObject item = member(method.name, method.desc, method.access);
            item.add("exceptions", strings(method.exceptions));
            int instructions = 0;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction.getOpcode() >= 0) {
                    instructions++;
                }
            }
            item.addProperty("instructionCount", instructions);
            methods.add(item);
        }
        result.add("methods", methods);
        return result;
    }

    private JsonObject decompile(JsonObject args) throws ToolException {
        ClassNode node = findClass(args);
        DecompileService.Service backend = DecompileService.Service.valueOf(text(args, "backend", "VineFlower"));
        try {
            String sourceText = DecompileService.decompile(jar, node.getInternalName(), backend);
            JsonObject result = response();
            result.addProperty("name", node.getInternalName());
            result.addProperty("backend", backend.name());
            result.addProperty("sha256", digest(node.getInternalName() + ".class", node.getClassBytes()));
            result.addProperty("source", sourceText);
            return result;
        } catch (RuntimeException e) {
            throw new ToolException("DECOMPILATION_FAILED", ArchiveTools.message(e));
        }
    }

    private JsonObject disassemble(JsonObject args) throws ToolException {
        ClassNode wrapper = findClass(args);
        StringWriter output = new StringWriter();
        PrintWriter writer = new PrintWriter(output);
        if (args.has("method")) {
            boolean found = false;
            for (MethodNode method : wrapper.getNodeInternal().methods) {
                if (method.name.equals(args.get("method").getAsString())
                        && (!args.has("descriptor") || method.desc.equals(args.get("descriptor").getAsString()))) {
                    writer.println(method.name + method.desc);
                    Textifier printer = new Textifier();
                    method.accept(new TraceMethodVisitor(printer));
                    printer.print(writer);
                    found = true;
                }
            }
            if (!found) {
                throw new ToolException("METHOD_NOT_FOUND", "No method matches the supplied name and descriptor");
            }
        } else {
            if (args.has("descriptor")) {
                throw new ToolException("INVALID_ARGUMENT", "descriptor requires method");
            }
            wrapper.getNodeInternal().accept(new TraceClassVisitor(writer));
        }
        writer.flush();
        JsonObject result = response();
        result.addProperty("name", wrapper.getInternalName());
        result.addProperty("format", "asm-textifier");
        result.addProperty("text", output.toString());
        return result;
    }

    private JsonObject search(JsonObject args) throws ToolException {
        String kind = args.get("kind").getAsString();
        String query = args.get("query").getAsString();
        String internalQuery = query.replace('.', '/');
        int opcode = kind.equals("opcode") ? opcode(query) : -1;
        Page page = new Page(args);
        search:
        for (String className : classes()) {
            checkCancelled();
            org.objectweb.asm.tree.ClassNode node = jar.getClassNode(className).getNodeInternal();
            if (kind.equals("class")) {
                if (className.contains(internalQuery) && page.include()) {
                    JsonObject hit = new JsonObject();
                    hit.addProperty("className", className);
                    page.items.add(hit);
                }
            } else if (kind.equals("field")) {
                for (FieldNode field : node.fields) {
                    if ((field.name.contains(query) || field.desc.contains(internalQuery)) && page.include()) {
                        JsonObject hit = member(field.name, field.desc, field.access);
                        hit.addProperty("className", className);
                        page.items.add(hit);
                    }
                    if (page.full()) {
                        break search;
                    }
                }
            } else {
                for (MethodNode method : node.methods) {
                    if (kind.equals("method")) {
                        if ((method.name.contains(query) || method.desc.contains(internalQuery)) && page.include()) {
                            JsonObject hit = member(method.name, method.desc, method.access);
                            hit.addProperty("className", className);
                            page.items.add(hit);
                        }
                    } else {
                        int index = 0;
                        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext(), index++) {
                            boolean matches = switch (kind) {
                                case "string" -> instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value && value.contains(query);
                                case "opcode" -> instruction.getOpcode() == opcode;
                                case "reference" -> referenceMatches(instruction, query, internalQuery);
                                default -> false;
                            };
                            if (matches && page.include()) {
                                JsonObject hit = new JsonObject();
                                hit.addProperty("className", className);
                                hit.addProperty("method", method.name);
                                hit.addProperty("descriptor", method.desc);
                                hit.addProperty("instructionIndex", index);
                                hit.addProperty("opcode", Printer.OPCODES[instruction.getOpcode()]);
                                if (instruction instanceof LdcInsnNode ldc) {
                                    hit.addProperty("value", String.valueOf(ldc.cst));
                                } else if (instruction instanceof MethodInsnNode reference) {
                                    hit.addProperty("owner", reference.owner);
                                    hit.addProperty("member", reference.name);
                                    hit.addProperty("targetDescriptor", reference.desc);
                                } else if (instruction instanceof FieldInsnNode reference) {
                                    hit.addProperty("owner", reference.owner);
                                    hit.addProperty("member", reference.name);
                                    hit.addProperty("targetDescriptor", reference.desc);
                                } else if (instruction instanceof TypeInsnNode reference) {
                                    hit.addProperty("target", reference.desc);
                                }
                                page.items.add(hit);
                            }
                            if (page.full()) {
                                break search;
                            }
                        }
                    }
                    if (page.full()) {
                        break search;
                    }
                }
            }
            if (page.full()) {
                break;
            }
        }
        JsonObject result = response();
        page.finish(result, "matches");
        return result;
    }

    private static boolean referenceMatches(AbstractInsnNode instruction, String query, String internalQuery) {
        if (instruction instanceof MethodInsnNode reference) {
            return reference.owner.contains(internalQuery) || reference.name.contains(query) || reference.desc.contains(internalQuery);
        }
        if (instruction instanceof FieldInsnNode reference) {
            return reference.owner.contains(internalQuery) || reference.name.contains(query) || reference.desc.contains(internalQuery);
        }
        return instruction instanceof TypeInsnNode reference && reference.desc.contains(internalQuery);
    }

    private static int opcode(String query) throws ToolException {
        try {
            int value = Integer.parseInt(query);
            if (value >= 0 && value < Printer.OPCODES.length && Printer.OPCODES[value] != null) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            for (int i = 0; i < Printer.OPCODES.length; i++) {
                if (query.equalsIgnoreCase(Printer.OPCODES[i])) {
                    return i;
                }
            }
        }
        throw new ToolException("INVALID_ARGUMENT", "Unknown JVM opcode: " + query);
    }

    private JsonObject readResource(JsonObject args) throws ToolException {
        String path = entryPath(args.get("path").getAsString());
        byte[] bytes = jar.getResource(path);
        if (bytes == null) {
            throw new ToolException("RESOURCE_NOT_FOUND", "Resource not found: " + path);
        }
        String encoding = text(args, "encoding", "utf8");
        int offset = number(args, "offset", 0);
        int limit = number(args, "limit", 65_536);
        int length;
        int end;
        String content;
        if (encoding.equals("base64")) {
            length = bytes.length;
            checkOffset(offset, length);
            end = (int) Math.min((long) offset + limit, length);
            content = Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, end));
        } else {
            String decoded;
            try {
                decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw new ToolException("INVALID_ENCODING", "Resource is not valid UTF-8. Read it with encoding=base64.");
            }
            length = decoded.codePointCount(0, decoded.length());
            checkOffset(offset, length);
            end = (int) Math.min((long) offset + limit, length);
            content = decoded.substring(decoded.offsetByCodePoints(0, offset), decoded.offsetByCodePoints(0, end));
        }
        JsonObject result = response();
        result.addProperty("path", path);
        result.addProperty("encoding", encoding);
        result.addProperty("size", bytes.length);
        result.addProperty("sha256", digest(path, bytes));
        result.addProperty("content", content);
        result.addProperty("offset", offset);
        result.addProperty("length", length);
        result.addProperty("hasMore", end < length);
        if (end < length) {
            result.addProperty("nextOffset", end);
        }
        return result;
    }

    private JsonObject writeResource(JsonObject args, BooleanSupplier cancelled) throws ToolException {
        checkRevision(args, cancelled);
        String path = ResourceEntryPath.validate(jar, args.get("path").getAsString());
        byte[] bytes = text(args, "encoding", "utf8").equals("base64")
                ? Base64.getDecoder().decode(args.get("content").getAsString())
                : encodeUtf8(args.get("content").getAsString());
        boolean changed = !Arrays.equals(bytes, jar.getResource(path));
        checkCancelled(cancelled);
        if (changed) {
            jar.putResource(path, bytes);
            revision++;
            resourceNames = null;
            digests.remove(path);
        }
        JsonObject result = response();
        result.addProperty("path", path);
        result.addProperty("changed", changed);
        result.addProperty("sha256", digest(path, bytes));
        return result;
    }

    private JsonObject deleteEntry(JsonObject args, BooleanSupplier cancelled) throws ToolException {
        checkRevision(args, cancelled);
        String path = entryPath(args.get("path").getAsString());
        checkCancelled(cancelled);
        if (jar.getResource(path) != null) {
            jar.removeResource(path);
            resourceNames = null;
        } else if (path.endsWith(".class") && jar.hasClass(path.substring(0, path.length() - 6))) {
            jar.removeClass(path.substring(0, path.length() - 6));
            classNames = null;
        } else {
            throw new ToolException("ENTRY_NOT_FOUND", "Entry not found: " + path);
        }
        revision++;
        digests.remove(path);
        JsonObject result = response();
        result.addProperty("deleted", path);
        return result;
    }

    private JsonObject compile(JsonObject args, BooleanSupplier cancelled) throws ToolException {
        checkRevision(args, cancelled);
        Map<String, String> sources = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : args.getAsJsonObject("sources").entrySet()) {
            if (entry.getKey().isBlank() || entry.getKey().contains("/")) {
                throw new ToolException("INVALID_ARGUMENT", "Source keys must be dotted class names: " + entry.getKey());
            }
            sources.put(entry.getKey(), entry.getValue().getAsString());
        }
        ArchiveCompilation.Change change;
        try {
            ArchiveCompilation staged = ArchiveCompilation.compile(ArchiveSnapshot.capture(jar), sources);
            change = staged.applyTo(jar, cancelled);
        } catch (CompileError failure) {
            JsonObject details = response();
            details.addProperty("diagnostics", ArchiveTools.message(failure));
            throw new ToolException("COMPILATION_FAILED", "Source did not compile. The archive is unchanged.", details);
        } catch (ConcurrentModificationException conflict) {
            throw new ToolException("REVISION_CONFLICT", conflict.getMessage(), response());
        }
        if (change.changed()) {
            revision++;
            classNames = null;
            change.generatedClasses().forEach(name -> digests.remove(name + ".class"));
            change.removedClasses().forEach(name -> digests.remove(name + ".class"));
        }
        JsonObject result = response();
        result.addProperty("changed", change.changed());
        result.add("generatedClasses", strings(change.generatedClasses()));
        result.add("removedClasses", strings(change.removedClasses()));
        return result;
    }

    private JsonObject export(JsonObject args, BooleanSupplier cancelled) throws IOException, ToolException {
        checkRevision(args, cancelled);
        String input = args.get("path").getAsString();
        if (input.isBlank()) {
            throw new ToolException("INVALID_ARGUMENT", "Output path must not be blank");
        }
        Path output = Path.of(input).toAbsolutePath().normalize();
        boolean overwrite = args.has("overwrite") && args.get("overwrite").getAsBoolean();
        ArchiveIO.write(jar, output, overwrite, ignored -> checkCancelled(cancelled));
        lastExport = output;
        JsonObject result = info();
        result.addProperty("outputPath", output.toString());
        return result;
    }

    private static byte[] encodeUtf8(String text) throws ToolException {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new ToolException("INVALID_ENCODING", "Resource text contains an unpaired Unicode surrogate. Supply valid Unicode text or base64 bytes.");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new ToolException("INVALID_ENCODING", "Resource text contains an unpaired Unicode surrogate. Supply valid Unicode text or base64 bytes.");
            }
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String entryPath(String path) throws ToolException {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.indexOf(':') >= 0) {
            throw new ToolException("INVALID_ARGUMENT", "Use a relative archive path with forward slashes");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0) {
                throw new ToolException("INVALID_ARGUMENT", "Invalid archive entry path: " + path);
            }
        }
        return path;
    }

    private String digest(String path, byte[] bytes) {
        return digests.computeIfAbsent(path, ignored -> {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError("JDK must provide SHA-256", e);
            }
        });
    }

    private static JsonObject member(String name, String descriptor, int access) {
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        result.addProperty("descriptor", descriptor);
        result.addProperty("access", access);
        return result;
    }

    private static JsonArray strings(Collection<String> values) {
        JsonArray result = new JsonArray();
        if (values != null) {
            values.forEach(result::add);
        }
        return result;
    }

    private static String text(JsonObject args, String name, String fallback) {
        return args.has(name) ? args.get(name).getAsString() : fallback;
    }

    private static int number(JsonObject args, String name, int fallback) {
        return args.has(name) ? args.get(name).getAsInt() : fallback;
    }

    private static void checkOffset(int offset, int length) throws ToolException {
        if (offset > length) {
            throw new ToolException("INVALID_ARGUMENT", "offset exceeds resource length: " + length);
        }
    }

    /** Allocates result objects only for the requested page and stops after one lookahead. */
    private static final class Page {
        private final int offset;
        private final int limit;
        private final JsonArray items = new JsonArray();
        private long seen;
        private boolean more;

        Page(JsonObject args) {
            offset = number(args, "offset", 0);
            limit = number(args, "limit", 100);
        }

        boolean include() {
            if (seen++ < offset) {
                return false;
            }
            if (items.size() == limit) {
                more = true;
                return false;
            }
            return true;
        }

        boolean full() {
            return more;
        }

        void finish(JsonObject result, String key) {
            result.add(key, items);
            result.addProperty("hasMore", more);
            if (more) {
                result.addProperty("nextOffset", (long) offset + items.size());
            }
        }
    }
}
