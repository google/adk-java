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

package com.google.adk.plugins.debuglogging;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.plugins.debuglogging.DebugEntry.Type;
import com.google.adk.plugins.debuglogging.TracePayload.MarkerTrace;
import com.google.adk.plugins.debuglogging.TracePayload.ToolResponseTrace;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Covers the guarantee the writer exists to give: a file of appended invocations that still parses
 * as YAML, and a failed write that costs the run nothing.
 *
 * <p>Assertions re-parse the file rather than matching its text, because "the trace can be read
 * back" is the actual promise — a separator bug or a stray document boundary is invisible to a
 * substring check.
 */
@RunWith(JUnit4.class)
public class DebugYamlWriterTest {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-02T10:15:30.123Z"), ZoneOffset.UTC);

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private Path tracePath;
  private DebugYamlWriter writer;
  private DebugTraceRecorder recorder;

  @Before
  public void setUp() throws Exception {
    tracePath = tempFolder.newFolder("traces").toPath().resolve("adk_debug.yaml");
    writer = new DebugYamlWriter(tracePath);
    recorder = new DebugTraceRecorder(FIXED);
  }

  private static InvocationContext contextFor(String invocationId) {
    return InvocationContext.builder()
        .invocationId(invocationId)
        .agent(LlmAgent.builder().name("root_agent").build())
        .session(Session.builder("session-1").appName("shop").userId("user-1").build())
        .sessionService(new InMemorySessionService())
        .runConfig(RunConfig.builder().build())
        .build();
  }

  /** One invocation, recorded and closed the way {@code afterRunCallback} will do it. */
  private InvocationDebugState invocation(String invocationId) {
    InvocationDebugState state = InvocationDebugState.of(contextFor(invocationId), recorder.now());
    recorder.start(state);
    recorder.record(invocationId, Type.USER_MESSAGE, MarkerTrace.INSTANCE);
    recorder.record(invocationId, Type.INVOCATION_END, MarkerTrace.INSTANCE);
    return state;
  }

  private ImmutableList<Map<String, Object>> readDocuments() throws Exception {
    ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    try (MappingIterator<Map<String, Object>> documents =
        yaml.readerFor(Map.class).readValues(tracePath.toFile())) {
      return ImmutableList.copyOf(documents.readAll());
    }
  }

  @Test
  public void append_twoInvocations_yieldsTwoDocumentsThatBothReparse() throws Exception {
    writer.append(invocation("inv-1"));
    writer.append(invocation("inv-2"));

    assertThat(readDocuments().stream().map(document -> document.get("invocation_id")))
        .containsExactly("inv-1", "inv-2")
        .inOrder();
  }

  /**
   * Jackson writes the {@code ---} itself. Upstream writes its own because PyYAML does not, and
   * porting that line literally would emit two separators and an empty document between them.
   */
  @Test
  public void append_writesExactlyOneDocumentSeparatorPerInvocation() throws Exception {
    writer.append(invocation("inv-1"));
    writer.append(invocation("inv-2"));

    List<String> lines = Files.readAllLines(tracePath, StandardCharsets.UTF_8);
    assertThat(lines.stream().filter(line -> line.startsWith("---")).count()).isEqualTo(2);
  }

  @Test
  public void append_keepsTheEntriesAndTheirTypes() throws Exception {
    writer.append(invocation("inv-1"));

    Object entries = readDocuments().get(0).get("entries");
    assertThat(entries).isInstanceOf(List.class);
    assertThat(((List<?>) entries).stream().map(entry -> ((Map<?, ?>) entry).get("entry_type")))
        .containsExactly("user_message", "invocation_end")
        .inOrder();
  }

  /**
   * The risk {@code MINIMIZE_QUOTES} introduces, pinned: a tool that returns the <em>string</em>
   * {@code "42"} must not read back as the number 42, or a trace would misreport what a tool said.
   */
  @Test
  public void append_stringsThatLookLikeScalars_stayQuotedAndReparseAsStrings() throws Exception {
    InvocationDebugState state = invocation("inv-1");
    recorder.record(
        "inv-1",
        Type.TOOL_RESPONSE,
        ToolResponseTrace.of(
            "lookup_order",
            "call-7",
            ImmutableMap.of("code", "42", "flag", "true", "missing", "null", "note", "in stock")));

    writer.append(state);

    Map<?, ?> result = (Map<?, ?>) entryData(readDocuments().get(0), 2).get("result");
    assertThat(result.get("code")).isEqualTo("42");
    assertThat(result.get("flag")).isEqualTo("true");
    assertThat(result.get("missing")).isEqualTo("null");
    assertThat(result.get("note")).isEqualTo("in stock");
  }

  /**
   * {@code LITERAL_BLOCK_STYLE} is the reason model output stays readable in a trace: a multi-line
   * answer must render as a {@code |-} block, not one line of {@code \n} escapes. The block form is
   * asserted in the file text because re-parsing alone cannot tell the two renderings apart.
   */
  @Test
  public void append_multiLineText_usesALiteralBlockRatherThanEscapes() throws Exception {
    InvocationDebugState state = invocation("inv-1");
    recorder.record(
        "inv-1",
        Type.TOOL_RESPONSE,
        ToolResponseTrace.of(
            "lookup_order", "call-7", ImmutableMap.of("summary", "line one\nline two")));

    writer.append(state);

    String file = Files.readString(tracePath, StandardCharsets.UTF_8);
    assertThat(file).contains("summary: |-");
    assertThat(file).doesNotContain("line one\\nline two");

    Map<?, ?> result = (Map<?, ?>) entryData(readDocuments().get(0), 2).get("result");
    assertThat(result.get("summary")).isEqualTo("line one\nline two");
  }

  private static Map<?, ?> entryData(Map<String, Object> document, int index) {
    List<?> entries = (List<?>) document.get("entries");
    return (Map<?, ?>) ((Map<?, ?>) entries.get(index)).get("data");
  }

  /**
   * The reason {@code append} is {@code synchronized}: the write runs on {@code Schedulers.io()},
   * so invocations that finish together arrive on different threads. Without the lock their
   * documents interleave and the file stops parsing — which this asserts by re-reading it, not by
   * inspecting the text.
   *
   * <p>A latch releases every thread at once so the appends genuinely contend, rather than being
   * serialized by the pool starting them one at a time.
   */
  @Test
  public void append_fromManyThreadsAtOnce_yieldsOneIntactDocumentEach() throws Exception {
    int writers = 8;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(writers);
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      for (int i = 0; i < writers; i++) {
        pool.execute(appendTask("inv-" + i, start, done));
      }
      start.countDown();
      assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    ImmutableList<Map<String, Object>> documents = readDocuments();
    assertThat(documents).hasSize(writers);
    assertThat(documents.stream().map(document -> document.get("invocation_id")))
        .containsExactly("inv-0", "inv-1", "inv-2", "inv-3", "inv-4", "inv-5", "inv-6", "inv-7");
  }

  /** One writer thread: wait for the starting gun, append, and report completion. */
  private Runnable appendTask(String invocationId, CountDownLatch start, CountDownLatch done) {
    InvocationDebugState state = invocation(invocationId);
    return () -> awaitThenAppend(state, start, done);
  }

  private void awaitThenAppend(
      InvocationDebugState state, CountDownLatch start, CountDownLatch done) {
    try {
      start.await();
      writer.append(state);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      done.countDown();
    }
  }

  @Test
  public void append_createsTheParentDirectoryRatherThanLosingTheTrace() throws Exception {
    Path nested = tempFolder.getRoot().toPath().resolve("a/b/c/adk_debug.yaml");

    new DebugYamlWriter(nested).append(invocation("inv-1"));

    assertThat(Files.exists(nested)).isTrue();
  }

  /** A full disk, a read-only path, a bad configuration: the run must not notice. */
  @Test
  public void append_whenThePathCannotBeWritten_logsInsteadOfThrowing() throws Exception {
    File blocker = tempFolder.newFile("not-a-directory");
    DebugYamlWriter blocked =
        new DebugYamlWriter(blocker.toPath().resolve("nested/adk_debug.yaml"));

    blocked.append(invocation("inv-1"));

    assertThat(blocker.length()).isEqualTo(0);
  }
}
