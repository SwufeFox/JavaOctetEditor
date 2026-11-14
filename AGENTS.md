# Repository Guidelines

## Project Overview

JavaOctetEditor is a Java 21 Swing desktop application and headless AI tool for inspecting, decompiling, editing, remapping, and exporting Java archives. The desktop, JSON CLI, and MCP stdio server share real archive/compiler/decompiler services. Edits remain in memory until explicitly exported.

## Architecture & Data Flow

`Main.main()` dispatches `--cli`/`--mcp` before desktop initialization, or launches `JavaOctetEditor` on the Swing event dispatch thread (EDT). The editor coordinates its `Jar`, typed settings, events, tasks, and components; desktop components use `JavaOctetEditor.getInstance()`. Shared archive/compiler/decompiler services must work without that singleton.

`ArchiveIO` loads archives, directories, or single classes into `Jar`. Capture `ArchiveSnapshot` on the model's owning thread, then process its independent data off-thread. `ArchiveCompilation.compile(snapshot, sources)` prepares all outputs; `applyTo` checks captured content and cancellation before committing once, preserves retained wrappers, and removes actual obsolete nested types. Desktop and AI source editing share this path. `Compiler.compileSources` isolates explicit archive dependencies and preserves target compatibility; `compileApplicationSources` intentionally exposes runtime libraries for visitor/transformer scripts. `ArchiveIO.write` stages output before committing; versioned multi-release classes remain resources.

`tool/ArchiveTools` owns explicit workspace handles and JSON Schemas, with short registry lifecycle locking and interruptible locks per handle. `ArchiveWorkspace` enforces revisions and shared staged edits. `McpServer` owns a bounded concurrent executor, persistent cancellation tokens, modern per-request metadata, and legacy initialization; `ToolApplication` provides CLI/lifecycle management. Keep protocol stdout free of logging.

## Key Directories

Java paths below are relative to `src/main/java/cn/enaium/joe/`:

- `gui/`, `dialog/`: Swing panels, menus, tabs, tree components, and dialogs.
- `jar/`, `util/classes/`, `util/asm/`: archive state, owned snapshots, guarded source commits, class wrappers, and bytecode helpers.
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
./gradlew desktopSmoke
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
- Editable text implements `gui/editor/EditorDraft`; use `DraftSupport` for asynchronous apply/discard/cancel and revalidate its consent before closing or replacing views. Preserve dirty buffers during refresh. Route user loads through `requestOpenArchive`; `ArchiveDocumentState` keeps exported content separate from newer model edits and load authorization.
- Reuse `ArchiveEntryIndex` for metadata-only class/resource lookup and `ResourceEntryPath.validate` for resource writes in desktop/tools. Base classes and versioned class resources have different editing paths.
- Implement desktop work as `AbstractTask<T>` through `TaskManager`. Capture input/configuration before dispatch; publish model changes through EDT success hooks only if work remains authoritative. `LoadRequestState` tracks newer load intent independently of success/failure. `ArchiveChange` guards remap/transform publication. Cancellation must survive a library clearing interrupts; close workers/timers with the editor.
- Source compilation uses `ArchiveCompilation` rather than duplicated apply loops. Preserve the original Java release and distinguish archive dependencies from intentional application-extension dependencies. New source targets must be unambiguous. Nested-class cleanup follows ownership metadata; `$` alone does not prove a class is nested.
- Perform Swing mutations on EDT. Future callbacks can execute immediately on their attaching thread; marshal UI work explicitly. Never block EDT with `Future.get()`/`join()`. `ASyncUtil.execute(work, ui)` is the existing background/UI helper; headless tools do not use desktop tasks.
- Report user-facing failures through `MessageUtil.error(...)`; use tinylog for diagnostics. Check each operation's failure contract: existing helpers may return `null` or `false`, while task futures may complete exceptionally.
- Represent settings with existing `Config`/`Value` types and register instances before loading. `ConfigManager(Path)` supports headless persistence; preserve recovery files and atomic-save behavior. Pass formatting arguments directly to `LangUtil.i18n(key, args...)`, and update both `lang/en_US.json` and `lang/zh_CN.json` when adding keys.
- AI mutations require current `expectedRevision`. Per-handle locking keeps revision checks atomic while independent handles can progress concurrently. Check persistent request/registry/handle cancellation before committing, including export progress callbacks; an unrelated operation abort still needs a response. Default exports must not overwrite files. Keep schemas and validation aligned, paginate results, and reject malformed Unicode text instead of silently replacing bytes.

## Important Files

- `src/main/java/cn/enaium/joe/Main.java`: executable entry point and tooling initialization.
- `src/main/java/cn/enaium/joe/JavaOctetEditor.java`: application composition, shared state, configuration registration, and archive refresh.
- `src/main/java/cn/enaium/joe/jar/Jar.java` and `src/main/java/cn/enaium/joe/util/classes/ClassNode.java`: archive indexing and tree/byte serialization invariants.
- `src/main/java/cn/enaium/joe/jar/ArchiveSnapshot.java`, `ArchiveCompilation.java`: owned background input and one-shot optimistic source commits shared by desktop/tools.
- `src/main/java/cn/enaium/joe/tool/ArchiveTools.java`: shared tool catalog/validation; workspace, CLI, and MCP implementations are beside it.
- `build.gradle`: Java toolchain, full runtime dependency packaging, JUnit configuration, application/agent manifest, desktop `run`, and explicit `desktopSmoke` task. Shadow includes runtime dependencies and merges service files; keep the runnable and plain JAR names distinct.
- `settings.gradle`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`: project name, artifact version, and Gradle distribution. The UI also declares `JavaOctetEditor.VERSION`; keep it aligned when changing releases.
- `.github/workflows/build_snapshot.yml`, `.github/workflows/build_release.yml`: Ubuntu/Temurin 21 builds and JAR artifact publishing; releases use manual dispatch.

## Runtime/Tooling Preferences

Use a full JDK 21: source compilation requires `ToolProvider.getSystemJavaCompiler()`, and attachment needs JDK tooling. Use the checked-in Gradle 8.10.2 wrapper. Dependencies resolve through Maven Central, JitPack, QuiltMC, and Cleanroom. Headless entry points must not initialize Swing or emit diagnostics on stdout. Settings use the launch working directory; `./gradlew run` isolates them in ignored `run/`. Replacing existing archive/config output requires atomic filesystem replacement.

## Testing & QA

Tests use JUnit Jupiter 5.11.3 via JUnit Platform under `src/test/java/cn/enaium/joe/`, grouped by subsystem with `*Test.java` names. Cover consumer-visible invariants: snapshot isolation, conflicting/one-shot commits, legacy target/API compatibility, explicit compiler contexts, archive preservation, task publication/load intent, persistent cancellation, settings recovery, and protocol concurrency. Use deterministic barriers for races and real bytecode/resources; avoid tests coupled to registry monitors or generated wording.

Tests run headlessly. Use focused classes while iterating (for example, `./gradlew test --tests 'cn.enaium.joe.tool.ArchiveToolsTest'`); `./gradlew build` matches CI and verifies packaging. No coverage percentage or lint threshold is configured. Run `./gradlew desktopSmoke` in a graphical session for desktop changes: its `src/smoke/java/` source exercises the packaged JAR's load, navigation, editing, resource, close, and export flows. Each run isolates fixtures/settings/screenshots under ignored `build/desktop-smoke/`; it is separate from default `test`/`build`. Test protocol streams and process exit behavior when changing headless entry points.
