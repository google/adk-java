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
  private static final String PATHEXT = ".COM;.EXE;.BAT;.CMD";

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void resolve_onNonWindows_returnsCommandUnchanged() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("npx.cmd"));

    String resolved = WindowsExecutableResolver.resolve("npx", LINUX, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo("npx");
  }

  @Test
  public void resolve_withNullCommand_returnsNull() {
    assertThat(WindowsExecutableResolver.resolve(null, WINDOWS, "C:\\bin", PATHEXT)).isNull();
  }

  @Test
  public void resolve_withEmptyCommand_returnsEmpty() {
    assertThat(WindowsExecutableResolver.resolve("", WINDOWS, "C:\\bin", PATHEXT)).isEmpty();
  }

  @Test
  public void resolve_withExplicitPath_returnsPathUnchanged() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("npx.cmd"));
    String explicit = dir.resolve("npx.cmd").toString();

    assertThat(WindowsExecutableResolver.resolve(explicit, WINDOWS, dir.toString(), PATHEXT))
        .isEqualTo(explicit);
  }

  @Test
  public void resolve_withForwardSlashPath_returnsPathUnchanged() {
    assertThat(WindowsExecutableResolver.resolve("tools/npx", WINDOWS, "C:\\bin", PATHEXT))
        .isEqualTo("tools/npx");
  }

  @Test
  public void resolve_whenOnlyCmdShimExists_returnsAbsoluteShimPath() throws Exception {
    // Regression test for google/adk-java#1686: npm installs npx as a batch shim, so a bare "npx"
    // must resolve to npx.cmd or CreateProcess fails with error=2.
    Path dir = temporaryFolder.newFolder("nodejs").toPath();
    Path shim = createFile(dir.resolve("npx.cmd"));

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo(shim.toFile().getAbsolutePath());
  }

  @Test
  public void resolve_whenExeAlsoExists_prefersPathextOrder() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    Path exe = createFile(dir.resolve("npx.exe"));
    createFile(dir.resolve("npx.cmd"));

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo(exe.toFile().getAbsolutePath());
  }

  @Test
  public void resolve_whenCommandAlreadyHasExtension_returnsAbsolutePath() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    Path shim = createFile(dir.resolve("npx.cmd"));

    String resolved =
        WindowsExecutableResolver.resolve("npx.cmd", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo(shim.toFile().getAbsolutePath());
  }

  @Test
  public void resolve_withMultiplePathEntries_searchesThemInOrder() throws Exception {
    Path empty = temporaryFolder.newFolder("empty").toPath();
    Path second = temporaryFolder.newFolder("second").toPath();
    Path shim = createFile(second.resolve("npx.cmd"));
    // Windows PATH is always ';'-separated, whatever platform the test runs on.
    String path = empty + ";" + second;

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, path, PATHEXT);

    assertThat(resolved).isEqualTo(shim.toFile().getAbsolutePath());
  }

  @Test
  public void resolve_whenCommandIsNotFound_returnsCommandUnchanged() throws Exception {
    Path dir = temporaryFolder.newFolder("empty").toPath();

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo("npx");
  }

  @Test
  public void resolve_withNullPath_returnsCommandUnchanged() {
    assertThat(WindowsExecutableResolver.resolve("npx", WINDOWS, null, PATHEXT)).isEqualTo("npx");
  }

  @Test
  public void resolve_withoutPathext_fallsBackToDefaultExtensions() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    Path shim = createFile(dir.resolve("npx.cmd"));

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), null);

    assertThat(resolved).isEqualTo(shim.toFile().getAbsolutePath());
  }

  @Test
  public void resolve_withCustomPathext_honoursIt() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    createFile(dir.resolve("npx.cmd"));
    Path custom = createFile(dir.resolve("npx.bat"));

    String resolved = WindowsExecutableResolver.resolve("npx", WINDOWS, dir.toString(), ".BAT");

    assertThat(resolved).isEqualTo(custom.toFile().getAbsolutePath());
  }

  @Test
  public void resolve_isCaseInsensitiveForOsName() throws Exception {
    Path dir = temporaryFolder.newFolder("bin").toPath();
    Path shim = createFile(dir.resolve("npx.cmd"));

    String resolved = WindowsExecutableResolver.resolve("npx", "WINDOWS", dir.toString(), PATHEXT);

    assertThat(resolved).isEqualTo(shim.toFile().getAbsolutePath());
  }

  private static Path createFile(Path path) throws IOException {
    return Files.createFile(path);
  }
}
