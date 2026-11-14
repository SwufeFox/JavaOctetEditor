package cn.enaium.joe.jar;

import cn.enaium.joe.util.classes.ClassNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Headless archive loading and transactional ZIP export. */
public final class ArchiveIO {
    private static final IntConsumer NO_PROGRESS = progress -> { };
    private static final int BUFFER_SIZE = 8192;

    private ArchiveIO() {
    }

    public static Jar read(Path input) throws IOException {
        return read(input, NO_PROGRESS);
    }

    /**
     * Reads a ZIP/JAR, directory or single class completely before returning it.
     * Versioned classes remain resources under META-INF/versions/ so they cannot
     * replace base classes in the internal-name index. Unsafe or colliding entry
     * names and misplaced base class entries are rejected.
     */
    public static Jar read(Path input, IntConsumer progress) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(progress, "progress");
        checkInterrupted();
        progress.accept(0);
        Path source = input.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        Jar archive = new Jar();
        byte[] buffer = new byte[BUFFER_SIZE];
        if (attributes.isDirectory()) {
            readDirectory(source, archive, progress, buffer);
        } else if (attributes.isRegularFile()) {
            if (source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class")) {
                try (InputStream stream = Files.newInputStream(source)) {
                    ClassNode node = parseClass(readBytes(stream, buffer), source.toString());
                    classEntryName(node);
                    archive.putClassNode(node);
                }
            } else {
                readZip(source, archive, progress, buffer);
            }
        } else {
            throw new IOException("Input is not a regular file or directory: " + source);
        }
        checkInterrupted();
        progress.accept(100);
        checkInterrupted();
        return archive;
    }

    private static void readDirectory(Path root, Jar archive, IntConsumer progress, byte[] buffer) throws IOException {
        EntryNames names = new EntryNames();
        try (Stream<Path> walk = Files.walk(root)) {
            var paths = walk.filter(path -> !path.equals(root)).sorted().toList();
            int completed = 0;
            for (Path path : paths) {
                checkInterrupted();
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                String name = root.relativize(path).toString().replace('\\', '/');
                if (attributes.isDirectory()) {
                    name = names.add(name + "/");
                    archive.putResource(name, new byte[0]);
                } else if (attributes.isRegularFile()) {
                    name = names.add(name);
                    try (InputStream stream = Files.newInputStream(path)) {
                        addEntry(archive, name, readBytes(stream, buffer));
                    }
                } else {
                    throw new IOException("Unsupported directory entry: " + path);
                }
                reportProgress(progress, ++completed, paths.size());
            }
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static void readZip(Path source, Jar archive, IntConsumer progress, byte[] buffer) throws IOException {
        EntryNames names = new EntryNames();
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var entries = zip.entries();
            int completed = 0;
            while (entries.hasMoreElements()) {
                checkInterrupted();
                ZipEntry entry = entries.nextElement();
                String name = names.add(entry.getName());
                byte[] bytes;
                try (InputStream stream = zip.getInputStream(entry)) {
                    bytes = readBytes(stream, buffer);
                }
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if ((entry.getSize() >= 0 && entry.getSize() != bytes.length)
                        || (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue())) {
                    throw new IOException("Corrupt ZIP entry: " + name);
                }
                if (name.endsWith("/")) {
                    if (bytes.length != 0) throw new IOException("Directory entry contains data: " + name);
                    archive.putResource(name, bytes);
                } else {
                    addEntry(archive, name, bytes);
                }
                reportProgress(progress, ++completed, zip.size());
            }
        }
    }

    private static void addEntry(Jar archive, String name, byte[] bytes) throws IOException {
        if (name.endsWith(".class") && !name.startsWith("META-INF/versions/")) {
            ClassNode node = parseClass(bytes, name);
            String expectedName = classEntryName(node);
            if (!name.equals(expectedName)) {
                throw new IOException("Class entry " + name + " declares " + node.getInternalName());
            }
            if (archive.hasClass(node.getInternalName())) {
                throw new IOException("Duplicate class: " + node.getInternalName());
            }
            archive.putClassNode(node);
        } else {
            archive.putResource(name, bytes);
        }
    }

    private static ClassNode parseClass(byte[] bytes, String source) throws IOException {
        if (bytes.length < 4 || bytes[0] != (byte) 0xCA || bytes[1] != (byte) 0xFE
                || bytes[2] != (byte) 0xBA || bytes[3] != (byte) 0xBE) {
            throw new IOException("Invalid class file: " + source);
        }
        try {
            return ClassNode.of(bytes);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid class file: " + source, exception);
        }
    }

    public static void write(Jar archive, Path output) throws IOException {
        write(archive, output, true, NO_PROGRESS);
    }

    public static void write(Jar archive, Path output, boolean overwrite) throws IOException {
        write(archive, output, overwrite, NO_PROGRESS);
    }

    public static void write(Jar archive, Path output, IntConsumer progress) throws IOException {
        write(archive, output, true, progress);
    }

    /**
     * Writes a ZIP to a sibling temporary file before committing it. Existing
     * output is atomically replaced when overwrite is true; otherwise even a
     * target created immediately before commit is preserved. Exporting over a
     * loaded input is safe because read holds no open handles. Filesystems
     * without atomic replacement support fail without a non-atomic fallback.
     * Callers must not mutate the archive during an export.
     */
    public static void write(Jar archive, Path output, boolean overwrite, IntConsumer progress) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Path destination = prepareOutput(output, progress);
        writeEntries(outputEntries(archive), destination, overwrite, progress);
    }

    public static void write(ArchiveSnapshot snapshot, Path output, IntConsumer progress) throws IOException {
        write(snapshot, output, true, progress);
    }

    /** Writes captured bytes directly, without rebuilding or serializing ASM trees. */
    public static void write(ArchiveSnapshot snapshot, Path output, boolean overwrite, IntConsumer progress) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Path destination = prepareOutput(output, progress);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        EntryNames names = new EntryNames();
        for (Map.Entry<String, byte[]> entry : snapshot.classBytes().entrySet()) {
            checkInterrupted();
            entries.put(names.add(classEntryName(entry.getKey())), entry.getValue());
        }
        addResources(entries, names, snapshot.resourceBytes());
        writeEntries(entries, destination, overwrite, progress);
    }

    private static Path prepareOutput(Path output, IntConsumer progress) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(progress, "progress");
        checkInterrupted();
        progress.accept(0);
        Path destination = output.toAbsolutePath().normalize();
        if (destination.getParent() == null || Files.isDirectory(destination)) {
            throw new IOException("Output must be a file: " + destination);
        }
        return destination;
    }

    private static void writeEntries(Map<String, byte[]> entries, Path destination, boolean overwrite,
                                     IntConsumer progress) throws IOException {
        Path parent = destination.getParent();
        checkInterrupted();
        Path temporary = Files.createTempFile(parent, ".joe-", ".tmp");
        try {
            try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(temporary))) {
                int completed = 0;
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    checkInterrupted();
                    ZipEntry zipEntry = new ZipEntry(entry.getKey());
                    zipEntry.setTime(0);
                    stream.putNextEntry(zipEntry);
                    byte[] bytes = entry.getValue();
                    for (int offset = 0; offset < bytes.length; ) {
                        checkInterrupted();
                        int count = Math.min(BUFFER_SIZE, bytes.length - offset);
                        stream.write(bytes, offset, count);
                        offset += count;
                    }
                    stream.closeEntry();
                    reportProgress(progress, ++completed, entries.size());
                }
            }
            // Notify before committing so observer failure or cancellation cannot replace output.
            progress.accept(100);
            checkInterrupted();
            try {
                if (overwrite) {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, destination);
                }
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Output filesystem does not support atomic archive replacement: " + destination, exception);
            }
        } catch (IOException | RuntimeException | Error exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static Map<String, byte[]> outputEntries(Jar archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        EntryNames names = new EntryNames();
        for (ClassNode node : archive.getClasses()) {
            checkInterrupted();
            String name = names.add(classEntryName(node));
            try {
                entries.put(name, node.getClassBytes());
            } catch (RuntimeException exception) {
                throw new IOException("Could not serialize class: " + name, exception);
            }
        }
        addResources(entries, names, archive.getResources());
        return entries;
    }

    private static void addResources(Map<String, byte[]> entries, EntryNames names,
                                     Map<String, byte[]> resources) throws IOException {
        for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
            checkInterrupted();
            String name = names.add(entry.getKey());
            byte[] bytes = entry.getValue();
            if (bytes == null || (name.endsWith("/") && bytes.length != 0)) {
                throw new IOException("Invalid resource data: " + name);
            }
            entries.put(name, bytes);
        }
    }

    private static String classEntryName(ClassNode node) throws IOException {
        return classEntryName(node.getInternalName());
    }

    private static String classEntryName(String internalName) throws IOException {
        if (internalName == null || internalName.isEmpty() || internalName.endsWith("/")
                || internalName.indexOf('.') >= 0 || internalName.indexOf(';') >= 0
                || internalName.indexOf('[') >= 0 || internalName.indexOf('\\') >= 0) {
            throw new IOException("Invalid class internal name: " + internalName);
        }
        return normalizeEntryName(internalName + ".class");
    }

    private static byte[] readBytes(InputStream stream, byte[] buffer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            checkInterrupted();
            int count = stream.read(buffer);
            if (count < 0) return bytes.toByteArray();
            bytes.write(buffer, 0, count);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Archive operation interrupted");
        }
    }

    private static void reportProgress(IntConsumer progress, int completed, int total) {
        progress.accept(total == 0 ? 0 : (int) Math.min(99, (long) completed * 100 / total));
    }

    private static String normalizeEntryName(String name) throws IOException {
        if (name == null) throw new IOException("Null archive entry name");
        String normalized = name.replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.indexOf('\0') >= 0
                || (normalized.length() >= 2 && Character.isLetter(normalized.charAt(0)) && normalized.charAt(1) == ':')) {
            throw new IOException("Invalid archive entry name: " + name);
        }
        String path = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("Invalid archive entry name: " + name);
            }
        }
        return normalized;
    }

    private static final class EntryNames {
        private final Set<String> entries = new HashSet<>();
        private final Set<String> files = new HashSet<>();
        private final Set<String> directories = new HashSet<>();

        private String add(String rawName) throws IOException {
            String name = normalizeEntryName(rawName);
            if (!entries.add(name)) throw new IOException("Duplicate archive entry: " + name);
            boolean directory = name.endsWith("/");
            String path = directory ? name.substring(0, name.length() - 1) : name;
            if (files.contains(path) || (!directory && directories.contains(path))) {
                throw new IOException("File/directory collision: " + name);
            }
            for (int separator = path.indexOf('/'); separator >= 0; separator = path.indexOf('/', separator + 1)) {
                String parent = path.substring(0, separator);
                if (files.contains(parent)) throw new IOException("File/directory collision: " + name);
                directories.add(parent);
            }
            if (directory) directories.add(path);
            else files.add(path);
            return name;
        }
    }
}
