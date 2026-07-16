/*
 * Copyright 2025 Google LLC
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

package com.google.adk.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.adk.web.config.AgentLoadingProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link CompiledAgentLoader}, focused on the opt-in directory confinement. */
public class CompiledAgentLoaderTest {

  @Test
  public void confineToSourceDir_defaultsOff() {
    // Off by default to preserve existing behavior; a warning is logged recommending it be enabled.
    assertFalse(new AgentLoadingProperties().isConfineToSourceDir());
  }

  @Test
  public void isDirWithinSourceRoot_confinementOff_allowsOutsideDir(@TempDir Path tmp)
      throws Exception {
    Path root = Files.createDirectory(tmp.resolve("root"));
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    CompiledAgentLoader loader = newLoader(root, /* confine= */ false);

    assertTrue(loader.isDirWithinSourceRoot(outside));
  }

  @Test
  public void isDirWithinSourceRoot_confinementOn_blocksOutsideAllowsInside(@TempDir Path tmp)
      throws Exception {
    Path root = Files.createDirectory(tmp.resolve("root"));
    Path inside = Files.createDirectories(root.resolve("target").resolve("classes"));
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    CompiledAgentLoader loader = newLoader(root, /* confine= */ true);

    assertTrue(loader.isDirWithinSourceRoot(inside));
    assertFalse(loader.isDirWithinSourceRoot(outside));
  }

  @Test
  public void isDirWithinSourceRoot_confinementOn_blocksSymlinkEscape(@TempDir Path tmp)
      throws Exception {
    Path root = Files.createDirectory(tmp.resolve("root"));
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    // A symlink inside the source root that points outside it must not be accepted.
    Path escape = Files.createSymbolicLink(root.resolve("escape"), outside);
    CompiledAgentLoader loader = newLoader(root, /* confine= */ true);

    assertFalse(loader.isDirWithinSourceRoot(escape));
  }

  private static CompiledAgentLoader newLoader(Path sourceDir, boolean confine) {
    AgentLoadingProperties props = new AgentLoadingProperties();
    props.setSourceDir(sourceDir.toString());
    props.setConfineToSourceDir(confine);
    return new CompiledAgentLoader(props);
  }
}
