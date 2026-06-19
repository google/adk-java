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
package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.reasoning.FakeLlm;
import com.google.adk.reasoning.InMemoryReasoningBankService;
import com.google.adk.reasoning.LlmMemoryExtractor;
import com.google.adk.reasoning.LlmTrajectoryJudge;
import com.google.adk.reasoning.ReasoningMemoryItem;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * End-to-end proof that the ReasoningBank closed loop composes under a real {@link InMemoryRunner}:
 * a FAILED run is judged, distilled to a guardrail, stored, then retrieved and injected as a
 * de-privileged user turn into a later run. Deterministic (three scripted {@link FakeLlm}s); proves
 * wiring/persistence/placement, not behavioral efficacy.
 */
@RunWith(JUnit4.class)
public final class ReasoningBankClosedLoopTest {

  private static final String APP_NAME = "poc";
  private static final String USER_ID = "user";
  // Shared plain-alphabetic keyword between the stored guardrail title and the run-2 query.
  private static final String KEYWORD = "pagination";

  private static LlmAgent agent(FakeLlm model) {
    return LlmAgent.builder()
        .model(model)
        .name("bugfixer")
        .description("Fixes bugs")
        .instruction("You are a debugging assistant. Answer concisely.")
        .build();
  }

  private static Content userText(String text) {
    return Content.fromParts(Part.fromText(text));
  }

  @Test
  public void closedLoop_failureRun_distillsGuardrail_thenInjectsDeprivileged() {
    FakeLlm agentLlm = FakeLlm.returningText("Here is my fix: wrap the call in a null check.");
    FakeLlm judgeLlm =
        FakeLlm.returningText("{\"thoughts\":\"unverified claim\",\"status\":\"failure\"}");
    FakeLlm extractorLlm =
        FakeLlm.returningText(
            "[{\"title\":\"Pagination guardrail\",\"description\":\"when paging\","
                + "\"content\":\"Verify the page id before loading more results.\"}]");

    InMemoryReasoningBankService service = new InMemoryReasoningBankService();
    ReasoningBankPlugin plugin =
        new ReasoningBankPlugin(
            service,
            APP_NAME,
            new LlmTrajectoryJudge(judgeLlm),
            new LlmMemoryExtractor(extractorLlm),
            /* autoConsolidate= */ true);

    InMemoryRunner runner = new InMemoryRunner(agent(agentLlm), APP_NAME, List.of(plugin));
    Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();

    // RUN 1 — consume to terminal so the concatWith-ed consolidation completes.
    runner
        .runAsync(USER_ID, session.id(), userText("Fix the bug in the report query."))
        .blockingSubscribe();

    // Assertion 1: a FAILURE-derived guardrail was distilled + stored through the Runner.
    List<ReasoningMemoryItem> stored =
        service.searchMemoryItems(APP_NAME, KEYWORD).blockingGet().memoryItems();
    assertThat(stored).isNotEmpty();
    assertThat(stored.get(0).sourceTraceSuccessful()).isFalse();
    assertThat(judgeLlm.lastRequest).isNotNull(); // judge stage traversed
    assertThat(extractorLlm.lastRequest).isNotNull(); // extract stage traversed

    // RUN 2 — a query sharing KEYWORD so retrieval matches; consume to terminal.
    runner
        .runAsync(USER_ID, session.id(), userText("Help with a " + KEYWORD + " bug."))
        .blockingSubscribe();

    // Assertion 2: the guardrail is injected as a DE-PRIVILEGED user turn, not a system
    // instruction.
    String userChannel = agentLlm.lastUserText();
    String systemChannel = agentLlm.lastSystemText();
    assertThat(userChannel).contains("<<<BEGIN_MEMORY>>>");
    assertThat(userChannel).contains("Verify the page id");
    assertThat(systemChannel).doesNotContain("<<<BEGIN_MEMORY>>>");
    assertThat(systemChannel).doesNotContain("Verify the page id");
  }
}
