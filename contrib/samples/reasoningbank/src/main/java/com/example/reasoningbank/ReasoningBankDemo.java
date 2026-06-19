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

package com.example.reasoningbank;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.adk.plugins.ReasoningBankPlugin;
import com.google.adk.reasoning.InMemoryReasoningBankService;
import com.google.adk.reasoning.LlmMemoryExtractor;
import com.google.adk.reasoning.LlmTrajectoryJudge;
import com.google.adk.reasoning.ReasoningMemoryItem;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;

/**
 * Live proof: a tool-less agent's ungroundable answer is judged FAILURE and distilled to a
 * guardrail.
 */
public final class ReasoningBankDemo {

  private static final String APP = "reasoningbank-demo";
  private static final String USER = "demo-user";
  private static final String MODEL_NAME = "gemini-2.5-flash";

  public static void main(String[] args) {
    if (System.getenv("GOOGLE_API_KEY") == null || System.getenv("GOOGLE_API_KEY").isEmpty()) {
      System.err.println(
          "Set GOOGLE_API_KEY to run this demo (https://aistudio.google.com/app/apikey).");
      return;
    }

    BaseLlm model = new Gemini(MODEL_NAME, System.getenv("GOOGLE_API_KEY"));

    InMemoryReasoningBankService bank = new InMemoryReasoningBankService();
    ReasoningBankPlugin plugin =
        new ReasoningBankPlugin(
            bank,
            APP,
            new LlmTrajectoryJudge(model),
            new LlmMemoryExtractor(model),
            /* autoConsolidate= */ true);

    LlmAgent agent =
        LlmAgent.builder()
            .model(MODEL_NAME)
            .name("bugfixer")
            .description("Fixes bugs")
            .instruction(
                "You are a debugging assistant with NO tools and NO access to source code. "
                    + "Answer the user's question directly.")
            .build();

    InMemoryRunner runner = new InMemoryRunner(agent, APP, List.of(plugin));
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();

    // Failure-leaning: the agent cannot ground an exhaustive claim -> strict judge tends to
    // FAILURE.
    String task =
        "List EVERY method in the class com.acme.OrderService that can throw "
            + "NullPointerException, and cite the exact line number of each.";
    System.out.println("\n=== RUN (failure-leaning task) ===\nUser > " + task + "\nAgent > ");
    Flowable<Event> events =
        runner.runAsync(USER, session.id(), Content.fromParts(Part.fromText(task)));
    events.blockingForEach(e -> System.out.println(e.stringifyContent()));

    // Consolidation has completed (concatWith). Show what the loop produced.
    List<ReasoningMemoryItem> items =
        bank.searchMemoryItems(APP, "NullPointerException method class")
            .blockingGet()
            .memoryItems();
    System.out.println("\n=== DISTILLED MEMORY (after judge -> extract -> store) ===");
    if (items.isEmpty()) {
      System.out.println(
          "Nothing distilled -- the judge returned SUCCESS or INDETERMINATE this run. "
              + "Re-run; the strict grounding rubric usually rules FAILURE on ungroundable tasks.");
      return;
    }
    for (ReasoningMemoryItem it : items) {
      System.out.printf(
          "[%s] %s%n  %s%n  %s%n",
          it.sourceTraceSuccessful() ? "strategy" : "GUARDRAIL (from failure)",
          it.title(),
          it.description(),
          it.content());
    }
    System.out.println(
        "\nRetrieval precheck: a later task sharing a keyword with the title above would inject this"
            + " item as a de-privileged memory turn.");
  }
}
