package cn.enaium.joe.tool;

import cn.enaium.joe.JavaOctetEditor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Headless entry points. This class never initializes the desktop application. */
public final class ToolApplication {
    public static final String VERSION = JavaOctetEditor.VERSION;

    private ToolApplication() {
    }

    public static int run(String[] args, InputStream input, PrintStream output, PrintStream errors) {
        try {
            if (args.length == 0) {
                throw new UsageException("Select --help, --version, --mcp, or --cli.");
            }
            return switch (args[0]) {
                case "--help" -> {
                    requireOnlyMode(args);
                    help(output);
                    yield 0;
                }
                case "--version" -> {
                    requireOnlyMode(args);
                    JsonObject version = new JsonObject();
                    version.addProperty("version", VERSION);
                    emit(output, version);
                    yield 0;
                }
                case "--mcp" -> {
                    requireOnlyMode(args);
                    yield mcp(input, output, errors);
                }
                case "--cli" -> cli(parse(args), input, output);
                default -> throw new UsageException("Unknown mode: " + args[0]);
            };
        } catch (UsageException e) {
            failure(output, errors, "USAGE_ERROR", e.getMessage());
            return 2;
        } catch (ToolException e) {
            emit(output, e.toJson());
            errors.println(e.getMessage());
            return 1;
        } catch (IOException | RuntimeException e) {
            failure(output, errors, "CLI_ERROR", ArchiveTools.message(e));
            return 1;
        }
    }

    private static int mcp(InputStream input, OutputStream output, PrintStream errors) {
        try (ArchiveTools tools = new ArchiveTools(); McpServer server = new McpServer(tools, VERSION)) {
            server.run(input, output);
            return 0;
        } catch (IOException | RuntimeException e) {
            errors.println("MCP transport failed: " + ArchiveTools.message(e));
            return 1;
        }
    }

    private record Options(String tool, String json, String archive, String output, boolean overwrite) {
    }

    private static Options parse(String[] args) throws UsageException {
        if (args.length < 2 || args[1].startsWith("--")) {
            throw new UsageException("Expected --cli <tool|tools>.");
        }
        String json = null;
        String archive = null;
        String output = null;
        boolean overwrite = false;
        Set<String> seen = new HashSet<>();
        for (int index = 2; index < args.length; index++) {
            String arg = args[index];
            if (arg.startsWith("--")) {
                if (!Set.of("--archive", "--output", "--overwrite").contains(arg)) {
                    throw new UsageException("Unknown option: " + arg);
                }
                if (!seen.add(arg)) {
                    throw new UsageException("Duplicate option: " + arg);
                }
                if (arg.equals("--overwrite")) {
                    overwrite = true;
                } else {
                    if (++index == args.length || args[index].isBlank() || args[index].startsWith("--")) {
                        throw new UsageException("Expected a path after " + arg);
                    }
                    if (arg.equals("--archive")) {
                        archive = args[index];
                    } else {
                        output = args[index];
                    }
                }
            } else if (json == null) {
                json = arg;
            } else {
                throw new UsageException("Only one JSON argument, @file, or '-' is allowed.");
            }
        }
        if (overwrite && output == null) {
            throw new UsageException("--overwrite requires --output; for export_archive use its JSON overwrite field.");
        }
        if (args[1].equals("tools") && (json != null || archive != null || output != null)) {
            throw new UsageException("tools accepts no arguments or archive/output options.");
        }
        if (args[1].equals("open_archive") && archive != null) {
            throw new UsageException("open_archive takes its input path in JSON; omit --archive.");
        }
        if (args[1].equals("close_archive") && output != null) {
            throw new UsageException("Cannot export after close_archive.");
        }
        return new Options(args[1], json, archive, output, overwrite);
    }

    private static int cli(Options options, InputStream input, PrintStream output)
            throws UsageException, IOException, ToolException {
        try (ArchiveTools tools = new ArchiveTools()) {
            if (options.tool().equals("tools")) {
                JsonObject catalog = new JsonObject();
                catalog.add("tools", tools.listTools());
                emit(output, catalog);
                return 0;
            }
            if (!tools.hasTool(options.tool())) {
                throw new UsageException("Unknown tool: " + options.tool() + ". Use --cli tools to inspect available tools.");
            }
            JsonObject arguments = arguments(options.json(), input);
            if (options.archive() != null) {
                // A handle belongs to this invocation; a supplied handle can never name
                // the newly opened workspace. Reject it before performing filesystem work.
                if (arguments.has("archiveId")) {
                    throw new UsageException("Omit archiveId when using --archive; the handle is created for this invocation.");
                }
                JsonObject open = new JsonObject();
                open.addProperty("path", options.archive());
                JsonObject opened = tools.call("open_archive", open);
                arguments.add("archiveId", opened.get("archiveId"));
                if (acceptsRevision(tools, options.tool()) && !arguments.has("expectedRevision")) {
                    arguments.add("expectedRevision", opened.get("revision"));
                }
            }
            JsonObject result = tools.call(options.tool(), arguments);
            JsonObject response = new JsonObject();
            response.add("result", result);
            if (options.output() != null) {
                JsonObject export = new JsonObject();
                // Every workspace result, including open_archive, identifies the exact
                // resulting state. Never reuse the pre-mutation expectedRevision here.
                export.add("archiveId", result.get("archiveId"));
                export.add("expectedRevision", result.get("revision"));
                export.addProperty("path", options.output());
                export.addProperty("overwrite", options.overwrite());
                response.add("export", tools.call("export_archive", export));
            }
            emit(output, response);
            return 0;
        }
    }

    private static boolean acceptsRevision(ArchiveTools tools, String name) {
        for (JsonElement element : tools.listTools()) {
            JsonObject definition = element.getAsJsonObject();
            if (definition.get("name").getAsString().equals(name)) {
                return definition.getAsJsonObject("inputSchema").getAsJsonObject("properties")
                        .has("expectedRevision");
            }
        }
        return false;
    }

    private static JsonObject arguments(String argument, InputStream input) throws UsageException {
        if (argument == null) {
            return new JsonObject();
        }
        try {
            String json = argument.equals("-") ? new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    : argument.startsWith("@") ? Files.readString(Path.of(argument.substring(1)), StandardCharsets.UTF_8)
                    : argument;
            return ToolJson.object(json);
        } catch (IOException | InvalidPathException | JsonParseException e) {
            throw new UsageException("Cannot read JSON arguments: " + ArchiveTools.message(e));
        }
    }

    private static void requireOnlyMode(String[] args) throws UsageException {
        if (args.length != 1) {
            throw new UsageException(args[0] + " accepts no additional arguments.");
        }
    }

    private static void emit(PrintStream output, JsonObject value) {
        output.println(ToolJson.GSON.toJson(value));
        output.flush();
    }

    private static void failure(PrintStream output, PrintStream errors, String code, String message) {
        emit(output, new ToolException(code, message).toJson());
        errors.println(message);
    }

    private static void help(PrintStream output) {
        output.println("""
                JavaOctetEditor
                  java -jar JavaOctetEditor.jar [archive-path]    Open the desktop editor
                  java -jar JavaOctetEditor.jar --help
                  java -jar JavaOctetEditor.jar --version
                  java -jar JavaOctetEditor.jar --mcp             MCP over UTF-8 newline JSON on stdin/stdout
                  java -jar JavaOctetEditor.jar --cli tools       Discover tools and their JSON schemas
                  java -jar JavaOctetEditor.jar --cli <tool> [JSON|@arguments.json|-]
                      [--archive PATH] [--output PATH] [--overwrite]

                CLI options may appear before or after the JSON argument. Omitted arguments
                mean {}; only explicit '-' reads stdin. Argument files use UTF-8.
                --archive opens a private workspace and supplies archiveId and, when needed,
                expectedRevision unless supplied. Omit archiveId when using --archive.
                --output exports the successful operation's resulting revision; existing files
                require --overwrite. Tools never execute archive code or shell commands.
                CLI JSON: {"result": <tool-result>, "export": <optional-export-result>}.
                Discovery: {"tools": [...]}. Version: {"version": "..."}.
                Failure: {"error": {"code": "...", "message": "...", "details": ...}}.
                Exit status: 0 success, 1 tool/runtime failure, 2 invalid usage.
                """);
        output.flush();
    }

    private static final class UsageException extends Exception {
        private UsageException(String message) {
            super(message);
        }
    }
}
