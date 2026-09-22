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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.function.Predicate.not;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One {@link Content} as it appears in the trace — port of adk-python's {@code _serialize_content}.
 *
 * <p>The factory requires a content; callers holding an {@code Optional<Content>} use {@link
 * Optional#map} rather than passing null.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
record ContentTrace(Optional<String> role, ImmutableList<PartTrace> parts) {

  /** Always produced — an empty part list is still worth recording, as in the original. */
  static ContentTrace from(Content content) {
    return new ContentTrace(content.role(), traceParts(content));
  }

  private static ImmutableList<PartTrace> traceParts(Content content) {
    return content.parts().orElseGet(ImmutableList::of).stream()
        .map(PartTrace::from)
        .flatMap(Optional::stream)
        .collect(toImmutableList());
  }

  /**
   * One {@link Part} as it appears in the trace — the declared shape of the per-part dict that
   * adk-python builds in {@code _serialize_content}.
   *
   * <p>The record <em>is</em> the schema: the field names are the trace's keys, and a part kind
   * that is not declared here cannot appear in the output.
   */
  @JsonInclude(JsonInclude.Include.NON_ABSENT)
  record PartTrace(
      Optional<String> text,
      @JsonProperty("function_call") Optional<FunctionCallTrace> functionCall,
      @JsonProperty("function_response") Optional<FunctionResponseTrace> functionResponse,
      @JsonProperty("inline_data") Optional<InlineDataTrace> inlineData,
      @JsonProperty("file_data") Optional<FileDataTrace> fileData,
      @JsonProperty("code_execution_result") Optional<CodeExecutionResultTrace> codeExecutionResult,
      @JsonProperty("executable_code") Optional<ExecutableCodeTrace> executableCode) {

    /**
     * The part's trace form, or empty when it carries nothing worth recording — adk-python's {@code
     * if part_data:} guard.
     */
    static Optional<PartTrace> from(Part part) {
      PartTrace trace =
          new PartTrace(
              part.text().filter(not(String::isEmpty)),
              part.functionCall().map(FunctionCallTrace::from),
              part.functionResponse().map(FunctionResponseTrace::from),
              part.inlineData().map(InlineDataTrace::from),
              part.fileData().map(FileDataTrace::from),
              part.codeExecutionResult().map(CodeExecutionResultTrace::from),
              part.executableCode().map(ExecutableCodeTrace::from));
      return trace.isEmpty() ? Optional.empty() : Optional.of(trace);
    }

    /** Whether every declared field is absent, so the part would serialize to {@code {}}. */
    @JsonIgnore
    boolean isEmpty() {
      return Stream.of(
              text,
              functionCall,
              functionResponse,
              inlineData,
              fileData,
              codeExecutionResult,
              executableCode)
          .allMatch(Optional::isEmpty);
    }

    /**
     * A tool call the model requested.
     *
     * <p>{@code args} stays untyped because it genuinely is: a tool author chooses the argument
     * shape, so there is no schema to declare. It goes through {@link SafeSerializer} for the same
     * reason adk-python routes it through {@code _safe_serialize}.
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record FunctionCallTrace(
        Optional<String> id,
        Optional<String> name,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) ImmutableMap<String, Object> args) {

      static FunctionCallTrace from(FunctionCall call) {
        return new FunctionCallTrace(
            call.id(),
            call.name(),
            call.args().map(SafeSerializer::serializeMap).orElseGet(ImmutableMap::of));
      }
    }

    /**
     * What a tool returned.
     *
     * <p>{@code response} is untyped for the same reason as {@link FunctionCallTrace#args()}, and
     * is contained by {@link SafeSerializer}, which is not optional here: the stock mapper throws
     * on a perfectly legal tool result.
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record FunctionResponseTrace(
        Optional<String> id,
        Optional<String> name,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) ImmutableMap<String, Object> response) {

      static FunctionResponseTrace from(FunctionResponse functionResponse) {
        return new FunctionResponseTrace(
            functionResponse.id(),
            functionResponse.name(),
            functionResponse
                .response()
                .map(SafeSerializer::serializeMap)
                .orElseGet(ImmutableMap::of));
      }
    }

    /**
     * What an inline blob <em>is</em> — never what it contains.
     *
     * <p>This record has no field for the bytes, which is the point. adk-python drops them by
     * choosing not to copy them into a dict, commenting "Omit actual data to keep file size
     * manageable"; here the omission is structural, so a later edit cannot reintroduce a base64
     * wall by accident.
     */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record InlineDataTrace(
        @JsonProperty("mime_type") Optional<String> mimeType,
        @JsonProperty("display_name") Optional<String> displayName,
        @JsonProperty("_data_omitted") boolean dataOmitted) {

      static InlineDataTrace from(Blob blob) {
        return new InlineDataTrace(blob.mimeType(), blob.displayName(), true);
      }
    }

    /** A file reference carried by a part. */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record FileDataTrace(
        @JsonProperty("file_uri") Optional<String> fileUri,
        @JsonProperty("mime_type") Optional<String> mimeType) {

      static FileDataTrace from(FileData fileData) {
        return new FileDataTrace(fileData.fileUri(), fileData.mimeType());
      }
    }

    /** The outcome of an executed code block. */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record CodeExecutionResultTrace(Optional<String> outcome, Optional<String> output) {

      static CodeExecutionResultTrace from(CodeExecutionResult result) {
        return new CodeExecutionResultTrace(result.outcome().map(String::valueOf), result.output());
      }
    }

    /** A code block the model asked to run. */
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record ExecutableCodeTrace(Optional<String> language, Optional<String> code) {

      static ExecutableCodeTrace from(ExecutableCode executableCode) {
        return new ExecutableCodeTrace(
            executableCode.language().map(String::valueOf), executableCode.code());
      }
    }
  }
}
