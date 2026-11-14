package cn.enaium.joe.gui;

import cn.enaium.joe.util.LangUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Filesystem work for single-entry actions; never accesses a live archive off EDT. */
final class EntryFileIO {
    private EntryFileIO() {
    }

    static byte[] read(Path source, Runnable checkCancelled) throws IOException {
        checkCancelled.run();
        if (!Files.isRegularFile(source)) throw new IOException(LangUtil.i18n("entry.inputFile", source));
        try (InputStream input = Files.newInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                checkCancelled.run();
                output.write(buffer, 0, count);
            }
            checkCancelled.run();
            return output.toByteArray();
        }
    }

    record Destination(Path path, BasicFileAttributes original) {
        static Destination capture(Path target) throws IOException {
            Path path = target.toAbsolutePath().normalize();
            BasicFileAttributes attributes = null;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (java.nio.file.NoSuchFileException absent) {
                // The no-overwrite move below also protects targets created after the chooser closes.
            }
            if (path.getParent() == null || (attributes != null && !attributes.isRegularFile())) {
                throw new IOException(LangUtil.i18n("entry.outputFile", path));
            }
            return new Destination(path, attributes);
        }

        boolean exists() {
            return original != null;
        }

        private void verify() throws IOException {
            if (original == null) return;
            BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!current.isRegularFile() || !Objects.equals(original.fileKey(), current.fileKey())
                    || original.size() != current.size()
                    || !original.lastModifiedTime().equals(current.lastModifiedTime())
                    || !original.creationTime().equals(current.creationTime())) {
                throw new IOException(LangUtil.i18n("entry.outputChanged", path));
            }
        }
    }

    static void write(Destination destination, byte[] bytes, Runnable checkCancelled, IntConsumer progress) throws IOException {
        checkCancelled.run();
        Path temporary = Files.createTempFile(destination.path().getParent(), ".joe-entry-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                for (int offset = 0; offset < bytes.length; ) {
                    checkCancelled.run();
                    int count = Math.min(8192, bytes.length - offset);
                    output.write(bytes, offset, count);
                    offset += count;
                    progress.accept((int) ((long) offset * 99 / bytes.length));
                }
            }
            progress.accept(100);
            checkCancelled.run();
            destination.verify();
            checkCancelled.run();
            try {
                if (destination.exists()) {
                    Files.move(temporary, destination.path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, destination.path());
                }
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException(LangUtil.i18n("entry.atomicUnsupported", destination.path()), failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
