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

import static com.google.common.truth.Truth.assertThat;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link WindowsExecutableResolver}. */
@RunWith(JUnit4.class)
public final class WindowsExecutableResolverTest {

  private static final String WINDOWS = "Windows 11";
  private static final String LINUX = "Linux";

  /**
   * The default Windows value. Shim fixtures spell their extensions to match it, because the
   * lookup probes names verbatim: on Windows that is case-insensitive, on the test machine it is
   * not.
   */
  private static final String PATHEXT = ".COM;.EXE;.BAT;.CMD";

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void resolve_onNonWindows_returnsCommandUnchanged() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("npx.CMD"));

    String resolved = WindowsExecutableResolver.resolve("npx", LINUX, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo("npx");
  }

  @Test
  public void resolve_withEmptyCommand_returnsEmpty() {
    assertThat(WindowsExecutableResolver.resolve("", WINDOWS, "C:\\bin", PATHEXT)).isEmpty();
  }

  @Test
  public void resolve_withExplicitPath_returnsPathUnchanged() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("npx.CMD"));
    String explicit = dir.resolve("npx.CMD").toString();

    assertThat(WindowsExecutableResolver.resolve(explicit, WINDOWS, dir.toString(), PATHEXT))
        .isEqualTo(explicit);
  }

  @Test
  public void resolve_withForwardSlashPath_returnsPathUnchanged() {
    assertThat(WindowsExecutableResolver.resolve("tools/npx", WINDOWS, "C:\\bin", PATHEXT))
        .isEqualTo("tools/npx");
  }

  @Test
  public void resolve_whenOnlyCmdShimExists_returnsShimPath() throws Exception {
    // Regression test for google/adk-java#1686: npm installs npx as a batch shim, so a bare "npx"
    // must resolve to npx.cmd or CreateProcess fails with error=2.
    Path dir = temporaryFolder.newFolder("nodejs").toPath();
    Path shim = createFile(dir.resolve("npx.CMD"));

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo(shim.toAbsolutePath().toString());
  }

  @Test
  public void resolve_whenOnlyBatShimExists_returnsShimPath() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    Path shim = createFile(dir.resolve("npx.BAT"));

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo(shim.toAbsolutePath().toString());
  }

  @Test
  public void resolve_whenOnlyExeExists_returnsCommandUnchanged() throws Exception {
    // CreateProcess appends .exe itself, so this command starts today; resolving it would change
    // which binary runs (the JVM's own directory and the system directories come before PATH).
    Path dir = temporaryFolder.newFolder("jdk").toPath();
    createFile(dir.resolve("java.exe"));

    String resolved = WindowsExecutableResolver.resolve("java", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo("java");
  }

  @Test
  public void resolve_whenExeExistsOnAnotherPathEntry_returnsCommandUnchanged() throws Exception {
    // A shim earlier on PATH must not shadow a command that CreateProcess can already start.
    Path shimDir = temporaryFolder.newFolder("nodejs").toPath();
    Path exeDir = temporaryFolder.newFolder("bin").toPath();
    createFile(shimDir.resolve("npx.CMD"));
    createFile(exeDir.resolve("npx.exe"));
    String path = shimDir + ";" + exeDir;

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, path, PATHEXT);

    assertThat(resolved).isEqualTo("npx");
  }

  @Test
  public void resolve_whenOnlyNonShimExtensionExists_returnsCommandUnchanged() throws Exception {
    // CreateProcess cannot run a .js file, and neither can a shell; leave it to ProcessBuilder.
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("server.JS"));

    String resolved =
        WindowsExecutableResolver.resolve("server", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo("server");
  }

  @Test
  public void resolve_whenCommandAlreadyHasShimExtension_returnsCommandUnchanged() throws Exception {
    // A bare "npx.cmd" already names the file CreateProcess needs; no lookup is required.
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("npx.CMD"));

    String resolved =
        WindowsExecutableResolver.resolve("npx.cmd", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo("npx.cmd");
  }

  @Test
  public void resolve_withMultiplePathEntries_searchesThemInOrder() throws Exception {
    Path empty = temporaryFolder.newFolder("empty").toPath();
    Path second = temporaryFolder.newFolder("second").toPath();
    Path shim = createFile(second.resolve("npx.CMD"));
    // Windows PATH is always ';'-separated, whatever platform the test runs on.
    String path = empty + ";" + second;

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, path, PATHEXT);

    assertThat(resolved).isEqualTo(shim.toAbsolutePath().toString());
  }

  @Test
  public void resolve_whenCommandIsNotFound_returnsCommandUnchanged() throws Exception {
    Path dir = temporaryFolder.newFolder("empty").toPath();

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo("npx");
  }

  @Test
  public void resolve_withMalformedPathEntry_returnsCommandUnchanged() {
    // NUL is invalid in a path on every platform, so this stands in for the malformed PATH entries
    // (a quoted one, say) that must be skipped rather than abort the lookup.
    assertThat(WindowsExecutableResolver.resolve("npx", WINDOWS, "C:\\bin\0", PATHEXT))
        .isEqualTo("npx");
  }

  @Test
  public void resolve_withNullPath_returnsCommandUnchanged() {
    assertThat(WindowsExecutableResolver.resolve("npx", WINDOWS, null, PATHEXT)).isEqualTo("npx");
  }

  @Test
  public void resolve_withoutPathext_fallsBackToDefaultExtensions() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    Path shim = createFile(dir.resolve("npx.CMD"));

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), null);

    assertThat(resolved).isEqualTo(shim.toAbsolutePath().toString());
  }

  @Test
  public void resolve_withCustomPathext_honoursIt() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("npx.CMD"));
    Path custom = createFile(dir.resolve("npx.BAT"));

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), ".BAT");

    assertThat(resolved).isEqualTo(custom.toAbsolutePath().toString());
  }

  @Test
  public void resolve_withPathextWithoutShimExtensions_returnsCommandUnchanged() throws Exception {
    // A shell would not find npx in this environment either, so neither should the resolver.
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("npx.CMD"));

    String resolved =
        WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), ".COM;.EXE");

    assertThat(resolved).isEqualTo("npx");
  }

  @Test
  public void resolve_isCaseInsensitiveForOsName() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    Path shim = createFile(dir.resolve("npx.CMD"));

    String resolved = WindowsExecutableResolver.resolve("npx", "WINDOWS", dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo(shim.toAbsolutePath().toString());
  }

  @CanIgnoreReturnValue
  private static Path createFile(Path path) throws IOException {
    return Files.createFile(path);
  }
}
