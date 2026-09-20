package com.yucareux.tellus.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Minecraft 26.3 reads input through SDL: the left mouse button is 1 (it was 0), the right one is 3, and keys are
 * scancodes (Enter is 40, not 257). These sources compile for 26.2 and 26.3 alike, so they must name buttons and keys
 * through {@code InputConstants} or the {@code InputWithModifiers} helpers, never by number.
 */
class InputCodeLiteralsTest {
   private static final List<String> CLIENT_SOURCES = List.of(
      "shared/mc262/src/client/java",
      "shared/mc263/src/client/java",
      "mc262/src/client/java",
      "mc263/src/client/java",
      "mc262-neoforge/src/client/java",
      "mc263-neoforge/src/client/java"
   );
   private static final Pattern RAW_INPUT_CODE = Pattern.compile(
      "(?:\\bbutton(?:\\(\\))?|\\bkey(?:Code)?(?:\\(\\))?|\\binput\\(\\))\\s*[!=]=\\s*\\d|GLFW_(?:KEY|MOUSE)_"
   );

   @Test
   void clientSourcesNeverCompareInputCodesWithNumbers() throws IOException {
      Path root = repositoryRoot();
      List<String> offenders = new ArrayList<>();
      int scanned = 0;

      for (String directory : CLIENT_SOURCES) {
         Path sources = root.resolve(directory);
         if (!Files.isDirectory(sources)) {
            continue;
         }

         try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
               scanned++;
               List<String> lines = Files.readAllLines(file);
               for (int index = 0; index < lines.size(); index++) {
                  if (RAW_INPUT_CODE.matcher(lines.get(index)).find()) {
                     offenders.add(root.relativize(file) + ":" + (index + 1) + " " + lines.get(index).trim());
                  }
               }
            }
         }
      }

      assertTrue(scanned > 20, "The client sources moved; only " + scanned + " files were scanned");
      assertEquals(List.of(), offenders, "Use InputConstants or event.isConfirmation() instead of input numbers");
   }

   private static Path repositoryRoot() {
      Path directory = Path.of("").toAbsolutePath();
      while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
         directory = directory.getParent();
      }

      assertTrue(directory != null, "No settings.gradle above " + Path.of("").toAbsolutePath());
      return directory;
   }
}
