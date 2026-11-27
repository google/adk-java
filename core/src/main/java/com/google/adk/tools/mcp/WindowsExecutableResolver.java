/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.tools.mcp;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a Windows batch shim such as {@code npx.cmd} to a path {@code ProcessBuilder} can start.
 *
 * <p>{@code ProcessBuilder} delegates to Win32 {@code CreateProcess}, which appends only {@code
 * .exe} to a bare command and never consults {@code PATHEXT}, whereas a shell does. npm installs
 * its CLI tools as {@code .cmd} shims, so {@code command("npx")} never starts and the MCP client
 * waits out its initialization timeout (google/adk-java#1686).
 *
 * <p>Only {@code .cmd} and {@code .bat} are resolved, and only when {@code CreateProcess} has no
 * {@code .exe} to run: a command that starts today keeps starting the same way.
 */
final class WindowsExecutableResolver {

  private static final String WINDOWS_OS_PREFIX = "windows";
  private static final String PATH_ENV = "PATH";
  private static final String PATH_EXT_ENV = "PATHEXT";
  private static final String EXE_EXTENSION = ".exe";
  private static final Splitter SEMICOLON_SPLITTER = Splitter.on(';').omitEmptyStrings();

  /**
   * The extensions this class resolves, in the order to try them. These are the only ones a shell
   * starts and {@code CreateProcess} cannot; also the fallback when {@code PATHEXT} is unset, since
   * they are the shim entries of the default {@code PATHEXT}.
   */
  private static final ImmutableList<String> SHIM_EXTENSIONS = ImmutableList.of(".BAT", ".CMD");

  private WindowsExecutableResolver() {}

  /** Resolves {@code command} against the current process environment. */
  static String resolve(String command) {
    return resolve(
        command,
        System.getProperty("os.name"),
        System.getenv(PATH_ENV),
        System.getenv(PATH_EXT_ENV));
  }

  /**
   * Test seam for {@link #resolve(String)}: lets the platform, lookup path and {@code PATHEXT} be
   * supplied.
   *
   * <p>Returns {@code command} unchanged off Windows, for commands that are already paths, for
   * commands {@code CreateProcess} can start as they are, and when no shim is found; {@code
   * ProcessBuilder} then reports its own error, which is more useful than one from here.
   */
  static String resolve(
      String command, @Nullable String osName, @Nullable String path, @Nullable String pathExt) {
    if (!isWindows(osName) || hasPathSeparator(command) || isNullOrEmpty(path)) {
      return command;
    }
    List<String> directories = SEMICOLON_SPLITTER.splitToList(path);
    if (hasExeOnPath(directories, command)) {
      return command;
    }
    for (String directory : directories) {
      for (String extension : shimExtensions(pathExt)) {
        Path shim = existingFile(directory, command + extension);
        if (shim != null) {
          return shim.toAbsolutePath().toString();
        }
      }
    }
    return command;
  }

  /**
   * Returns whether a {@code PATH} entry holds {@code command.exe}, which {@code CreateProcess}
   * finds on its own. Such a command works today, so it is left alone.
   */
  private static boolean hasExeOnPath(List<String> directories, String command) {
    for (String directory : directories) {
      if (existingFile(directory, command + EXE_EXTENSION) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code directory/fileName} when it is a regular file, or {@code null} when it is absent
   * or the entry is malformed.
   */
  private static @Nullable Path existingFile(String directory, String fileName) {
    try {
      Path candidate = Path.of(directory, fileName);
      return Files.isRegularFile(candidate) ? candidate : null;
    } catch (InvalidPathException e) {
      // A malformed PATH entry, such as one left quoted by a botched setx, is skipped rather than
      // failing the lookup.
      return null;
    }
  }

  /**
   * Returns the extensions to try, in {@code PATHEXT} order, restricted to {@link #SHIM_EXTENSIONS}.
   *
   * <p>The probe keeps the spelling from {@code PATHEXT} as-is: Windows matches file names
   * case-insensitively, so {@code npx.CMD} finds the {@code npx.cmd} that npm wrote.
   */
  private static List<String> shimExtensions(@Nullable String pathExt) {
    if (isNullOrEmpty(pathExt)) {
      return SHIM_EXTENSIONS;
    }
    ImmutableList.Builder<String> extensions = ImmutableList.builder();
    for (String extension : SEMICOLON_SPLITTER.split(pathExt)) {
      if (SHIM_EXTENSIONS.contains(extension.toUpperCase(Locale.ROOT))) {
        extensions.add(extension);
      }
    }
    return extensions.build();
  }

  private static boolean isWindows(@Nullable String osName) {
    return osName != null && osName.toLowerCase(Locale.ROOT).startsWith(WINDOWS_OS_PREFIX);
  }

  private static boolean hasPathSeparator(String command) {
    return command.indexOf('/') >= 0 || command.indexOf('\\') >= 0;
  }
}
