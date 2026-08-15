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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends one invocation to the trace file as a YAML document — the write block in adk-python's
 * {@code after_run_callback}.
 *
 * <p>Two differences from the original, both forced by the JVM:
 *
 * <ul>
 *   <li><b>The append is {@code synchronized}.</b> The write runs on {@code Schedulers.io()}, so
 *       two invocations finishing together would otherwise interleave their documents into a file
 *       that no longer parses. One document is written whole or not at all. The lock is this
 *       instance's, so it orders the appends of one plugin; two plugins pointed at the same path
 *       are not coordinated.
 *   <li><b>Missing parent directories are created</b>, so a path pointing into a directory that
 *       does not exist yet still produces a trace.
 * </ul>
 *
 * <p>The document separator comes from Jackson, which writes {@code ---} at the start of every
 * document by default — so, unlike upstream, this class does not write one itself. Writing both
 * would produce two separators per invocation and a file that reads as if documents were missing.
 *
 * <p>Nothing here throws: a failed write is logged and the run continues.
 */
final class DebugYamlWriter {

  private static final Logger logger = LoggerFactory.getLogger(DebugYamlWriter.class);

  private static final String WROTE_TRACE = "Wrote debug data for invocation {} to {}";
  private static final String FAILED_TO_WRITE = "Failed to write debug data for invocation {}";

  private final Path outputPath;
  private final ObjectMapper mapper = configure(new ObjectMapper(yamlFactory()));

  /**
   * Applies the trace settings to {@code mapper} and returns it, for JSON or YAML alike — the tests
   * assert on the JSON form, this class writes the YAML one, and both must agree about which keys
   * exist.
   *
   * <p>Deliberately <em>not</em> {@code JsonBaseModel.getMapper()}: that instance is shared across
   * all of ADK and is configured with {@code Include.ALWAYS}, so changing it to suit a debug trace
   * would change everyone's serialization. The trace records here are ours, so they get their own
   * mapper.
   *
   * <p>{@link Jdk8Module} makes {@code Optional} fields serialize as their contents, and {@code
   * NON_ABSENT} omits the empty ones — together reproducing pydantic's {@code exclude_none=True}
   * without a stripping pass.
   */
  static <T extends ObjectMapper> T configure(T mapper) {
    mapper.registerModule(new Jdk8Module());
    mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
    return mapper;
  }

  /**
   * Jackson quotes every scalar by default; PyYAML quotes only what it must, so a mapper left at
   * its defaults writes a file that is correct but harder to read. Three features fix that without
   * changing what the file <em>means</em>:
   *
   * <ul>
   *   <li>{@code MINIMIZE_QUOTES} drops the quotes around ordinary text.
   *   <li>{@code ALWAYS_QUOTE_NUMBERS_AS_STRINGS} is <b>not optional alongside it.</b> {@code
   *       MINIMIZE_QUOTES} alone still quotes a string reading {@code true} or {@code null}, but
   *       writes the string {@code "42"} bare — so a tool that returned a numeric string would be
   *       read back from the trace as a number, misreporting what the tool actually said. A test
   *       pins all four cases.
   *   <li>{@code LITERAL_BLOCK_STYLE} renders multi-line text as a {@code |-} block instead of one
   *       line of {@code \n} escapes. Model output is the bulk of these traces and is usually
   *       multi-line.
   * </ul>
   */
  private static YAMLFactory yamlFactory() {
    return new YAMLFactory()
        .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
        .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
        .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE);
  }

  DebugYamlWriter(Path outputPath) {
    this.outputPath = Preconditions.checkNotNull(outputPath);
  }

  /** Appends {@code state} as one document, or logs why it could not be. */
  synchronized void append(InvocationDebugState state) {
    try {
      write(mapper.writeValueAsString(state));
      logger.debug(WROTE_TRACE, state.invocationId(), outputPath);
    } catch (IOException | RuntimeException e) {
      logger.error(FAILED_TO_WRITE, state.invocationId(), e);
    }
  }

  private void write(String document) throws IOException {
    Path parent = outputPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(
        outputPath,
        document,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }
}
