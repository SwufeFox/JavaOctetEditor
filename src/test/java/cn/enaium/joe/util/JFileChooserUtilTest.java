package cn.enaium.joe.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JFileChooserUtilTest {
    @TempDir
    Path directory;

    @Test
    void suggestedLocationWinsOverRememberedAndFallback() throws Exception {
        Path suggested = Files.createDirectory(directory.resolve("suggested"));
        Path remembered = Files.createDirectory(directory.resolve("remembered"));
        Path fallback = Files.createDirectory(directory.resolve("fallback"));

        assertEquals(suggested.toFile(), JFileChooserUtil.initialDirectory(
                suggested.resolve("archive.jar").toFile(), remembered.toString(), fallback.toFile()));
    }

    @Test
    void rememberedDirectoryIsUsedWhenTheSuggestionCannotBeResolved() throws Exception {
        Path remembered = Files.createDirectory(directory.resolve("remembered"));
        Path fallback = Files.createDirectory(directory.resolve("fallback"));

        assertEquals(remembered.toFile(), JFileChooserUtil.initialDirectory(
                directory.resolve("missing/archive.jar").toFile(), remembered.toString(), fallback.toFile()));
    }

    @Test
    void invalidMemoryFallsBackWithoutThrowing() throws Exception {
        Path fallback = Files.createDirectory(directory.resolve("fallback"));

        assertEquals(fallback.toFile(), JFileChooserUtil.initialDirectory(
                null, directory.resolve("missing").toString(), fallback.toFile()));
        assertEquals(new java.io.File("."), JFileChooserUtil.initialDirectory(
                null, null, directory.resolve("missing").toFile()));
    }
}
