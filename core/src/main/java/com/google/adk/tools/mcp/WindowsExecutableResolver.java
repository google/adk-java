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
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a bare command name to the file Windows will actually execute.
 *
 * <p>On Windows, npm installs its command-line tools as batch shims ({@code npx.cmd}, {@code
 * npm.cmd}) rather than PE executables. {@code ProcessBuilder} delegates to Win32 {@code
 * CreateProcess}, which appends only {@code .exe} to an extension-less command and never consults
 * {@code PATHEXT}. A stdio server configured with {@code command("npx")} therefore fails with
 * {@code CreateProcess error=2} even though typing {@code npx} into a shell works — the shell does
 * the {@code PATHEXT} lookup, and {@code CreateProcess} does not.
 *
 * <p>That failure is invisible to callers: no subprocess is ever spawned, so the MCP client waits
 * out its initialization timeout instead of reporting the problem. Resolving the shim up front
 * makes the subprocess start, which is what this class is for. See google/adk-java#1686.
 *
 * <p>The lookup mirrors {@code shutil.which} as used by the Python MCP SDK, so the Java transport
 * behaves the same way on Windows as the other SDKs: a command that already ends with one of the
 * {@code PATHEXT} extensions is tried as-is, and any other command is tried with each {@code
 * PATHEXT} extension appended, in {@code PATHEXT} order.
 */
final class WindowsExecutableResolver {

  private static final String WINDOWS_OS_PREFIX = "windows";
  private static final String PATH_ENV = "PATH";
  private static final String PATH_EXT_ENV = "PATHEXT";
  private static final String PATH_SEPARATOR = ";";

  /**
   * Fallback for a Windows process whose {@code PATHEXT} is unset. Matches the value {@code
   * shutil.which} uses.
   */
  private static final String DEFAULT_PATH_EXT = ".COM;.EXE;.BAT;.CMD;.VBS;.JS;.WS;.MSC";

  private WindowsExecutableResolver() {}

  /**
   * Resolves {@code command} against the current process environment.
   *
   * <p>Returns {@code command} unchanged on non-Windows platforms, and also on Windows when no
   * matching file is found — a resolution miss must not fail the build, because {@code
   * ProcessBuilder} may still be able to start it (a relative path, or an entry on the current
   * directory) and its own error message is the more useful one.
   */
  static String resolve(String command) {
    requireNonNull(command, "command");
    String resolved =
        resolve(
            command,
            System.getProperty("os.name"),
            System.getenv(PATH_ENV),
            System.getenv(PATH_EXT_ENV));
    return resolved == null ? command : resolved;
  }

  /** Test seam for {@link #resolve(String)}: lets the platform and lookup path be supplied. */
  static @Nullable String resolve(
      @Nullable String command,
      @Nullable String osName,
      @Nullable String path,
      @Nullable String pathExt) {
    if (isNullOrEmpty(command) || !isWindows(osName) || hasPathSeparator(command)) {
      return command;
    }
    String found = which(command, path, pathExt);
    return found == null ? command : found;
  }

  /**
   * Returns the absolute path of the first {@code PATH} entry that contains {@code command}, or
   * {@code null} when there is none.
   *
   * <p>Empty {@code PATH} entries are skipped rather than read as the current directory: resolving
   * against the working directory would make the behaviour depend on where the caller happens to
   * run, and a miss falls back to {@code ProcessBuilder} anyway.
   */
  private static @Nullable String which(
      String command, @Nullable String path, @Nullable String pathExt) {
    if (isNullOrEmpty(path)) {
      return null;
    }
    List<String> fileNames = candidateFileNames(command, pathExt);
    for (String directory : path.split(PATH_SEPARATOR)) {
      if (directory.isEmpty()) {
        continue;
      }
      File dir = new File(directory);
      File[] entries = dir.listFiles();
      if (entries == null) {
        continue;
      }
      for (String fileName : fileNames) {
        File match = findEntry(entries, fileName);
        if (match != null) {
          return match.getAbsolutePath();
        }
      }
    }
    return null;
  }

  /**
   * Finds {@code fileName} among {@code entries}, ignoring case.
   *
   * <p>The match is deliberately case-insensitive rather than left to {@link File#isFile()}: this
   * class models Windows semantics, and the lookup has to give the same answer when it runs on a
   * case-sensitive filesystem (tests, or a build host that is not the target). Matching against the
   * directory listing also returns the name as it is spelled on disk, so the caller gets {@code
   * npx.cmd} rather than the {@code npx.CMD} built from {@code PATHEXT}.
   */
  private static @Nullable File findEntry(File[] entries, String fileName) {
    for (File entry : entries) {
      if (entry.getName().equalsIgnoreCase(fileName) && entry.isFile()) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Returns the file names to look for, in the order {@code shutil.which} tries them: the command
   * itself when it already carries a {@code PATHEXT} extension, otherwise the command with each
   * extension appended.
   */
  private static List<String> candidateFileNames(String command, @Nullable String pathExt) {
    List<String> extensions = splitPathExt(pathExt);
    String lowerCommand = command.toLowerCase(Locale.ROOT);
    for (String extension : extensions) {
      if (lowerCommand.endsWith(extension.toLowerCase(Locale.ROOT))) {
        return ImmutableList.of(command);
      }
    }
    ImmutableList.Builder<String> fileNames = ImmutableList.builder();
    for (String extension : extensions) {
      fileNames.add(command + extension);
    }
    return fileNames.build();
  }

  private static List<String> splitPathExt(@Nullable String pathExt) {
    String raw = isNullOrEmpty(pathExt) ? DEFAULT_PATH_EXT : pathExt;
    ImmutableList.Builder<String> extensions = ImmutableList.builder();
    for (String extension : raw.split(PATH_SEPARATOR)) {
      if (!extension.isEmpty()) {
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
