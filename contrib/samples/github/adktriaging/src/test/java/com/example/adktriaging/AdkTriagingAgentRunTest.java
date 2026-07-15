// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.example.adktriaging;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure prompt builders in {@link AdkTriagingAgentRun}. */
final class AdkTriagingAgentRunTest {

  @Test
  void buildBatchPrompt_includesCountAndTool() {
    String prompt = AdkTriagingAgentRun.buildBatchPrompt(3);
    assertThat(prompt).contains("3 issues");
    assertThat(prompt).contains("list_untriaged_issues");
  }

  @Test
  void buildSingleIssuePrompt_includesIssueDetailsAndFlags() {
    String prompt =
        AdkTriagingAgentRun.buildSingleIssuePrompt(
            42,
            "Crash on startup",
            "Stack trace here",
            /* needsComponentLabel= */ true,
            /* needsOwner= */ false,
            /* existingComponentLabel= */ null);

    assertThat(prompt).contains("#42");
    assertThat(prompt).contains("Crash on startup");
    assertThat(prompt).contains("Stack trace here");
    assertThat(prompt).contains("needs_component_label=true");
    assertThat(prompt).contains("needs_owner=false");
  }
}
