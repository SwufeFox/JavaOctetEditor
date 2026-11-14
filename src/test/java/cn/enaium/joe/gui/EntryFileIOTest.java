package cn.enaium.joe.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class EntryFileIOTest {
    @TempDir
    Path temporary;

    @Test
    void newlyCreatedOutputCannotBeOverwrittenWithoutConfirmation() throws Exception {
        Path target = temporary.resolve("resource.bin");
        EntryFileIO.Destination destination = EntryFileIO.Destination.capture(target);
        Files.write(target, new byte[]{7, 8});
        assertThrows(FileAlreadyExistsException.class,
                () -> EntryFileIO.write(destination, new byte[]{1, 2, 3}, () -> { }, progress -> { }));
        assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(target));
        assertOnlyTargetRemains(target);
    }

    @Test
    void cancellationAfterStagingPreservesExistingOutputAndCleansTemporaryFile() throws Exception {
        Path target = temporary.resolve("resource.bin");
        Files.write(target, new byte[]{7, 8});
        EntryFileIO.Destination destination = EntryFileIO.Destination.capture(target);
        assertThrows(CancellationException.class,
                () -> EntryFileIO.write(destination, new byte[20_000], () -> { }, progress -> {
                    if (progress == 100) throw new CancellationException();
                }));
        assertArrayEquals(new byte[]{7, 8}, Files.readAllBytes(target));
        assertOnlyTargetRemains(target);
    }

    @Test
    void changedConfirmedOutputIsPreservedAndBinaryExportIsExact() throws Exception {
        Path target = temporary.resolve("resource.bin");
        Files.write(target, new byte[]{7, 8});
        EntryFileIO.Destination stale = EntryFileIO.Destination.capture(target);
        Files.write(target, new byte[]{9, 10, 11});
        assertThrows(IOException.class,
                () -> EntryFileIO.write(stale, new byte[]{0}, () -> { }, progress -> { }));
        assertArrayEquals(new byte[]{9, 10, 11}, Files.readAllBytes(target));
        byte[] binary = new byte[]{0, (byte) 0xff, (byte) 0xca, (byte) 0xfe, 13, 10};
        EntryFileIO.write(EntryFileIO.Destination.capture(target), binary, () -> { }, progress -> { });
        assertArrayEquals(binary, Files.readAllBytes(target));
        assertOnlyTargetRemains(target);
    }

    private void assertOnlyTargetRemains(Path target) throws IOException {
        try (var files = Files.list(temporary)) {
            assertEquals(java.util.List.of(target), files.toList());
        }
    }
}
