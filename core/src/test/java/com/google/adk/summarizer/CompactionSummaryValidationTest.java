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

package com.google.adk.summarizer;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Contents;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** Exercises summary validation through persistence and construction of the next model request. */
@RunWith(Parameterized.class)
public final class CompactionSummaryValidationTest {
  private final boolean tailRetention;
  private final Content modelContent;
  private final @Nullable FinishReason finishReason;
  private final List<Part> summaryParts;

  @Parameters(name = "{0}: {1}")
  public static List<Object[]> cases() {
    Part thought =
        Part.fromText("Let me reason about the conversation.").toBuilder()
            .thought(true)
            .thoughtSignature(new byte[] {1, 2, 3})
            .build();
    Part first = Part.fromText("  Keep the staging database unchanged.\n");
    Part second =
        Part.fromText("The deployment is still pending.").toBuilder().thought(false).build();
    FinishReason stopped = new FinishReason(FinishReason.Known.STOP);
    List<Object[]> scenarios =
        ImmutableList.of(
            new Object[] {"missing parts", Content.builder().build(), ImmutableList.of(), stopped},
            new Object[] {
              "empty parts",
              Content.builder().parts(ImmutableList.of()).build(),
              ImmutableList.of(),
              stopped
            },
            new Object[] {
              "empty text", Content.fromParts(Part.fromText("")), ImmutableList.of(), stopped
            },
            new Object[] {
              "blank text", Content.fromParts(Part.fromText(" \n\t")), ImmutableList.of(), stopped
            },
            new Object[] {"thought only", Content.fromParts(thought), ImmutableList.of(), stopped},
            new Object[] {
              "signature only",
              Content.fromParts(Part.builder().thoughtSignature(new byte[] {1, 2}).build()),
              ImmutableList.of(),
              stopped
            },
            new Object[] {
              "tool call only",
              Content.fromParts(Part.fromFunctionCall("lookup", ImmutableMap.of())),
              ImmutableList.of(),
              stopped
            },
            new Object[] {
              "thought and summary",
              Content.fromParts(thought, first, second),
              ImmutableList.of(thought, first, second),
              stopped
            },
            new Object[] {
              "visible summary",
              Content.fromParts(first, second),
              ImmutableList.of(first, second),
              stopped
            },
            new Object[] {
              "truncated summary",
              Content.fromParts(Part.fromText("The deployment is still")),
              ImmutableList.of(),
              new FinishReason(FinishReason.Known.MAX_TOKENS)
            },
            new Object[] {
              "blocked summary",
              Content.fromParts(Part.fromText("I cannot summarize this conversation.")),
              ImmutableList.of(),
              new FinishReason(FinishReason.Known.SAFETY)
            },
            new Object[] {
              "other finish reason",
              Content.fromParts(first),
              ImmutableList.of(),
              new FinishReason(FinishReason.Known.OTHER)
            },
            new Object[] {
              "unknown finish reason",
              Content.fromParts(first),
              ImmutableList.of(),
              new FinishReason("CUSTOM_INCOMPLETE")
            },
            new Object[] {
              "unspecified finish reason",
              Content.fromParts(thought, first, second),
              ImmutableList.of(thought, first, second),
              new FinishReason(FinishReason.Known.FINISH_REASON_UNSPECIFIED)
            },
            new Object[] {
              "absent finish reason",
              Content.fromParts(thought, first, second),
              ImmutableList.of(thought, first, second),
              null
            });
    List<Object[]> cases = new ArrayList<>();
    for (boolean tailRetention : ImmutableList.of(false, true)) {
      for (Object[] scenario : scenarios) {
        cases.add(
            new Object[] {
              tailRetention ? "tail retention" : "sliding window",
              scenario[0],
              tailRetention,
              scenario[1],
              scenario[2],
              scenario[3]
            });
      }
    }
    return cases;
  }

  public CompactionSummaryValidationTest(
      String strategy,
      String scenario,
      boolean tailRetention,
      Content modelContent,
      List<Part> summaryParts,
      @Nullable FinishReason finishReason) {
    this.tailRetention = tailRetention;
    this.modelContent = modelContent;
    this.summaryParts = summaryParts;
    this.finishReason = finishReason;
  }

  @Test
  public void compactionPreservesUsableHistory() {
    InMemorySessionService sessions = new InMemorySessionService();
    Session session = sessions.createSession("app", "user", null, "session").blockingGet();
    Event instruction =
        event("user", "user", "Do not change the staging database.", 1L, "previous");
    Event answer = event("agent", "model", "I will wait for deployment approval.", 2L, "previous");
    var unused1 = sessions.appendEvent(session, instruction).blockingGet();
    var unused2 = sessions.appendEvent(session, answer).blockingGet();

    BaseLlm model = mock(BaseLlm.class);
    when(model.model()).thenReturn("test-model");
    Candidate.Builder candidate = Candidate.builder().content(modelContent);
    if (finishReason != null) {
      candidate.finishReason(finishReason);
    }
    LlmResponse response =
        LlmResponse.create(GenerateContentResponse.builder().candidates(candidate.build()).build());
    assertThat(response.content()).hasValue(modelContent);
    assertThat(response.finishReason()).isEqualTo(Optional.ofNullable(finishReason));
    when(model.generateContent(any(LlmRequest.class), eq(false)))
        .thenReturn(Flowable.just(response));
    LlmEventSummarizer summarizer = new LlmEventSummarizer(model);
    EventCompactor compactor =
        tailRetention
            ? new TailRetentionEventCompactor(summarizer, 0, 0)
            : new SlidingWindowEventCompactor(new EventsCompactionConfig(1, 0, summarizer));

    compactor.compact(session, sessions).blockingAwait();
    verify(model).generateContent(any(LlmRequest.class), eq(false));
    Event latest = event("user", "user", "What should happen next?", 3L, "next");
    var unused3 = sessions.appendEvent(session, latest).blockingGet();
    Session reloaded =
        sessions.getSession("app", "user", "session", Optional.empty()).blockingGet();
    InvocationContext context =
        InvocationContext.builder()
            .invocationId("next")
            .agent(LlmAgent.builder().name("agent").build())
            .session(reloaded)
            .sessionService(sessions)
            .build();
    List<Content> nextContents =
        new Contents()
            .processRequest(context, LlmRequest.builder().build())
            .blockingGet()
            .updatedRequest()
            .contents();

    if (summaryParts.isEmpty()) {
      assertThat(nextContents)
          .containsExactly(
              instruction.content().get(), answer.content().get(), latest.content().get())
          .inOrder();
      assertThat(reloaded.events()).hasSize(3);
    } else {
      Content expectedSummary = Content.builder().role("model").parts(summaryParts).build();
      assertThat(nextContents).containsExactly(expectedSummary, latest.content().get()).inOrder();
      assertThat(reloaded.events()).hasSize(4);
      Event summary =
          reloaded.events().stream()
              .filter(e -> e.actions().compaction().isPresent())
              .findFirst()
              .orElseThrow();
      assertThat(summary.actions().compaction().get().compactedContent())
          .isEqualTo(expectedSummary);
      assertThat(summary.actions().compaction().get().startTimestamp()).isEqualTo(1L);
      assertThat(summary.actions().compaction().get().endTimestamp()).isEqualTo(2L);
    }
    // Compaction changes the model's view, never the original stored conversation.
    assertThat(reloaded.events()).containsAtLeast(instruction, answer, latest).inOrder();
  }

  private static Event event(
      String author, String role, String text, long timestamp, String invocation) {
    return Event.builder()
        .id(author + "-" + timestamp)
        .author(author)
        .invocationId(invocation)
        .timestamp(timestamp)
        .content(Content.builder().role(role).parts(Part.fromText(text)).build())
        .build();
  }
}
