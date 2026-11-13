# Repository Guidelines

## Project Overview

JavaOctetEditor is a Java 21 Swing desktop application and headless AI tool for inspecting, decompiling, editing, remapping, and exporting Java archives. The desktop, JSON CLI, and MCP stdio server share real archive/compiler/decompiler services. Edits remain in memory until explicitly exported.

## Architecture & Data Flow

`Main.main()` dispatches `--cli`/`--mcp` before desktop initialization, or launches `JavaOctetEditor` on the Swing event dispatch thread (EDT). The editor coordinates its `Jar`, typed settings, events, tasks, and components; desktop components use `JavaOctetEditor.getInstance()`. Shared archive/compiler/decompiler services must work without that singleton.

`ArchiveIO` loads archives, directories, or single classes into `Jar`, separating base `ClassNode` objects from resource bytes. Desktop `InputJarTask` publishes a fully loaded model on EDT. `DecompileService.decompile(archive, name, backend)` selects VineFlower, CFR, or Procyon explicitly. `Compiler.compileSources(sources, classPath)` compiles against supplied bytecode and returns every generated class. Callers stage complete results before mutating their archive. `ArchiveIO.write` stages output before committing it; versioned multi-release classes remain resources at their original paths.

`tool/ArchiveTools` owns explicit, independent workspace handles and shared JSON Schemas. `ArchiveWorkspace` enforces revision checks and transactional edits. `McpServer` serves modern discovery/per-request metadata and legacy initialization over UTF-8 newline-delimited JSON-RPC; `ToolApplication` provides the CLI. Keep protocol stdout free of logging.

## Key Directories

Java paths below are relative to `src/main/java/cn/enaium/joe/`:

- `gui/`, `dialog/`: Swing panels, menus, tabs, tree components, and dialogs.
- `jar/`, `util/classes/`, `util/asm/`: archive state, class wrappers, and bytecode helpers.
- `util/task/tasks/`: load, export, search, remapping, and transformation operations.
- `service/decompiler/`, `util/compiler/`: decompiler backends and in-memory source compilation.
- `util/config/`, `util/event/`: typed settings, generated settings controls, and application events.
- `tool/`: headless workspace operations, structured errors, CLI, and MCP transport.

Resources live under `src/main/resources/`: `lang/` contains UI translations, `language/` contains Java/bytecode syntax definitions, and `icons/` contains SVG assets. Keep the two language directories distinct.

## Development Commands

Run from the repository root. These examples use the POSIX wrapper; use `gradlew.bat` with the same arguments in Windows shells.

```sh
./gradlew build
./gradlew test
./gradlew test --tests 'cn.enaium.joe.compiler.CompilerTest'
./gradlew run
java -jar build/libs/JavaOctetEditor-1.4.9.jar
java -jar build/libs/JavaOctetEditor-1.4.9.jar --cli tools
java -jar build/libs/JavaOctetEditor-1.4.9.jar --mcp
```

`build` runs tests and produces the runnable shaded JAR; `-plain.jar` lacks bundled dependencies. Match the filename to `gradle.properties` after version changes. `run` uses `run/` as the desktop working directory. No dedicated lint/format task is configured. See `README.md` for CLI argument files, export options, and MCP client setup.

## Code Conventions & Common Patterns

- Match surrounding Java formatting, generally four-space indentation and opening braces on the declaration line. Use PascalCase classes, camelCase members, and operation names ending in `Task` for background tasks; preserve existing license headers.
- Class-map keys use JVM internal names such as `pkg/Type`. Use `ClassNode.getClassNode()` for mutable ASM access and `getClassBytes()` for serialization; read-only metadata access should retain cached bytes. Archive index views are read-only: use explicit `Jar` put/remove methods. Refresh rejects colliding renames instead of discarding classes.
- Rebuilding/filtering the current archive's tree must preserve open editor tabs. `JavaOctetEditor.setJar` clears tabs when replacing the archive; `refreshJar` updates the current model and view without discarding the editing context.
- Implement tracked desktop work as `AbstractTask<T>` and submit through `TaskManager`. Preserve duplicate protection unless a task is `@Repeatable`; cancellation must reach its worker, and executors/timers must close with the editor. Compile all outputs before committing any class changes.
- Perform Swing mutations on EDT. Future callbacks can execute immediately on their attaching thread; marshal UI work explicitly. Never block EDT with `Future.get()`/`join()`. `ASyncUtil.execute(work, ui)` is the existing background/UI helper; headless tools do not use desktop tasks.
- Report user-facing failures through `MessageUtil.error(...)`; use tinylog for diagnostics. Check each operation's failure contract: existing helpers may return `null` or `false`, while task futures may complete exceptionally.
- Represent settings with existing `Config`/`Value` types and register instances before loading. `ConfigManager(Path)` supports headless persistence; preserve recovery files and atomic-save behavior. Pass formatting arguments directly to `LangUtil.i18n(key, args...)`, and update both `lang/en_US.json` and `lang/zh_CN.json` when adding keys.
- AI mutations require the workspace's current `expectedRevision`; conflicts must leave data unchanged. Do not execute inspected archive code or enable annotation processors. Keep tool input schemas aligned with runtime validation, paginate large results, and export only after successful changes. Default CLI/MCP exports must not overwrite existing files.

## Important Files

- `src/main/java/cn/enaium/joe/Main.java`: executable entry point and tooling initialization.
- `src/main/java/cn/enaium/joe/JavaOctetEditor.java`: application composition, shared state, configuration registration, and archive refresh.
- `src/main/java/cn/enaium/joe/jar/Jar.java` and `src/main/java/cn/enaium/joe/util/classes/ClassNode.java`: archive indexing and tree/byte serialization invariants.
- `src/main/java/cn/enaium/joe/tool/ArchiveTools.java`: shared tool catalog/validation; workspace, CLI, and MCP implementations are beside it.
- `build.gradle`: Java toolchain, full runtime dependency packaging, JUnit configuration, application/agent manifest, and desktop `run` task. Shadow includes runtime dependencies and merges service files; keep the runnable and plain JAR names distinct.
- `settings.gradle`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`: project name, artifact version, and Gradle distribution. The UI also declares `JavaOctetEditor.VERSION`; keep it aligned when changing releases.
- `.github/workflows/build_snapshot.yml`, `.github/workflows/build_release.yml`: Ubuntu/Temurin 21 builds and JAR artifact publishing; releases use manual dispatch.

## Runtime/Tooling Preferences

Use a full JDK 21: source compilation requires `ToolProvider.getSystemJavaCompiler()`, and attachment needs JDK tooling. Use the checked-in Gradle 8.10.2 wrapper. Dependencies resolve through Maven Central, JitPack, QuiltMC, and Cleanroom. Headless entry points must not initialize Swing or emit diagnostics on stdout. Settings use the launch working directory; `./gradlew run` isolates them in ignored `run/`. Replacing existing archive/config output requires atomic filesystem replacement.

## Testing & QA

Tests use JUnit Jupiter 5.11.3 via JUnit Platform under `src/test/java/cn/enaium/joe/`, grouped by subsystem with `*Test.java` names. Regression coverage includes archive/model invariants, real dependency/nested-class compilation, all decompiler backends, executable ASM visitors, task lifecycle, settings recovery, events, and real CLI/MCP workflows. Print-only generator tests were removed; assertions must verify consumer-visible behavior.

Tests run headlessly. Use focused classes while iterating (for example, `./gradlew test --tests 'cn.enaium.joe.tool.ArchiveToolsTest'`); `./gradlew build` matches CI and verifies packaging. No coverage percentage or lint threshold is configured. Desktop changes also require a runtime smoke check of affected load, browse, edit/recompile, and export behavior. Test protocol streams and process exit behavior when changing headless entry points.
