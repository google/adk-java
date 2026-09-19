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
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.adk.plugins.ReasoningBankPlugin;
import com.google.adk.reasoning.InMemoryReasoningBankService;
import com.google.adk.reasoning.LlmMemoryExtractor;
import com.google.adk.reasoning.LlmTrajectoryJudge;
import com.google.adk.reasoning.ReasoningMemoryItem;
import com.google.adk.reasoning.ReasoningTrace;
import com.google.adk.reasoning.Verdict;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;

/**
 * Live proof of the ReasoningBank closed loop, driven explicitly so each stage is visible.
 *
 * <p>Flow: run agent -> judge (prints verdict + rationale) -> extract (prints guardrail/strategy)
 * -> store -> retrieval precheck (confirms the minted item is immediately retrievable by its own
 * title).
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

    // 1. Model
    BaseLlm model = new Gemini(MODEL_NAME, System.getenv("GOOGLE_API_KEY"));

    // 2. Bank (empty at start)
    InMemoryReasoningBankService bank = new InMemoryReasoningBankService();

    // 3. Judge + extractor (reused explicitly below)
    LlmTrajectoryJudge judge = new LlmTrajectoryJudge(model);
    LlmMemoryExtractor extractor = new LlmMemoryExtractor(model);

    // 4. Tool-less agent wired into an InMemoryRunner with a retrieve-only plugin.
    //    The plugin has no judge/extractor, so afterRunCallback is a no-op. Consolidation is
    //    driven explicitly in steps 7-9 so every stage is visible in the demo output.
    LlmAgent agent =
        LlmAgent.builder()
            .model(MODEL_NAME)
            .name("BugFixAssistant")
            .description("Debugging assistant")
            .instruction(
                "You are a debugging assistant with NO tools and NO access to source code. "
                    + "Answer the user's question directly.")
            .build();

    InMemoryRunner runner =
        new InMemoryRunner(agent, APP, List.of(new ReasoningBankPlugin(bank, APP)));
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();

    // 5. Failure-leaning task: the agent cannot ground an exhaustive claim without source access.
    String task =
        "List EVERY method in com.acme.OrderService that can throw NullPointerException, "
            + "with exact line numbers.";

    // 6. Run the agent and capture its final answer.
    System.out.println("\n=== RUN (failure-leaning task) ===");
    System.out.println("User > " + task);
    System.out.println("Agent >");
    StringBuilder agentAnswer = new StringBuilder();
    runner
        .runAsync(USER, session.id(), Content.fromParts(Part.fromText(task)))
        .blockingForEach(
            e -> {
              String text = e.stringifyContent();
              System.out.println(text);
              agentAnswer.append(text);
            });

    // 7. Judge the trajectory explicitly and print the verdict with the judge's rationale.
    System.out.println("\n=== JUDGE VERDICT ===");
    ReasoningTrace trace =
        ReasoningTrace.builder().id(session.id()).task(task).output(agentAnswer.toString()).build();
    Verdict verdict = judge.judge(task, trace).blockingGet();
    System.out.println("Outcome  : " + verdict.outcome());
    System.out.println("Rationale: " + verdict.rationale());

    if (verdict.outcome() == Verdict.Outcome.INDETERMINATE) {
      System.out.println(
          "\nJudge produced no verdict (model/parse issue) -- re-run to get a deterministic"
              + " result.");
      return;
    }

    // 8. Extract memory items from the judged trajectory.
    System.out.println("\n=== DISTILLED MEMORY ===");
    boolean wasSuccess = verdict.outcome() == Verdict.Outcome.SUCCESS;
    ReasoningTrace judgedTrace = trace.toBuilder().successful(wasSuccess).build();
    List<ReasoningMemoryItem> distilled =
        extractor.extract(task, List.of(judgedTrace)).blockingGet();

    if (distilled.isEmpty()) {
      System.out.println("Extractor produced no memory item -- re-run to get a distilled result.");
      return;
    }
    for (ReasoningMemoryItem it : distilled) {
      System.out.printf(
          "[%s] %s%n  Description: %s%n  Content: %s%n",
          it.sourceTraceSuccessful() ? "strategy" : "GUARDRAIL (from failure)",
          it.title(),
          it.description(),
          it.content());
    }

    // 9. Store all distilled items in the bank.
    System.out.println("\n=== STORE ===");
    for (ReasoningMemoryItem it : distilled) {
      bank.storeMemoryItem(APP, it).blockingAwait();
    }
    System.out.println("Stored " + distilled.size() + " item(s) in the bank.");

    // 10. Retrieval precheck: query by the first item's own title to confirm immediate
    // retrievability.
    String probe = distilled.get(0).title();
    List<ReasoningMemoryItem> hits = bank.searchMemoryItems(APP, probe).blockingGet().memoryItems();
    System.out.println("\n=== RETRIEVAL PRECHECK ===");
    System.out.printf("Query=\"%s\": %d item(s) retrievable%n", probe, hits.size());
    if (!hits.isEmpty()) {
      System.out.println("First hit title: " + hits.get(0).title());
    }
  }
}
