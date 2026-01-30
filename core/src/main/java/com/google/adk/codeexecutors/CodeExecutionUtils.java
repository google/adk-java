/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may not use this file except in compliance with the License.
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

package com.google.adk.codeexecutors;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility functions for code execution. */
public final class CodeExecutionUtils {

  public static Part buildCodeExecutionResultPart(CodeExecutionResult result) {
    if (result.stderr() != null && !result.stderr().isEmpty()) {
      return Part.builder()
          .codeExecutionResult(
              com.google.genai.types.CodeExecutionResult.builder()
                  .outcome("FAILED")
                  .output(result.stderr())
                  .build())
          .build();
    }

    List<String> finalResult = new ArrayList<>();
    if ((result.stdout() != null && !result.stdout().isEmpty())
        || result.outputFiles() == null
        || result.outputFiles().isEmpty()) {
      finalResult.add("Code execution result:\n" + result.stdout() + "\n");
    }

    if (result.outputFiles() != null && !result.outputFiles().isEmpty()) {
      String savedArtifacts =
          "Saved artifacts:\n"
              + result.outputFiles().stream().map(f -> "`" + f.name() + "`").collect(joining(","));
      finalResult.add(savedArtifacts);
    }

    return Part.builder()
        .codeExecutionResult(
            com.google.genai.types.CodeExecutionResult.builder()
                .outcome("OK")
                .output(String.join("\n\n", finalResult))
                .build())
        .build();
  }

  public static Part buildExecutableCodePart(String code) {
    return Part.builder().executableCode(ExecutableCode.builder().code(code).build()).build();
  }

  /**
   * Converts the code execution parts to text parts in a Content.
   *
   * @param content The content to convert.
   * @param codeBlockDelimiters The delimiters to format the code block.
   * @param executionResultDelimiters The delimiters to format the code execution result.
   * @return The updated content.
   */
  public static Content convertCodeExecutionParts(
      Content content, List<String> codeBlockDelimiters, List<String> executionResultDelimiters) {
    if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
      return content;
    }

    ImmutableList<Part> originalParts = ImmutableList.copyOf(content.parts().get());
    Part lastPart = Iterables.getLast(originalParts);

    // Handle the conversion of trailing executable code parts.
    if (lastPart.executableCode().isPresent()) {
      List<Part> newParts = new ArrayList<>(originalParts);
      Part newPart =
          Part.fromText(
              codeBlockDelimiters.get(0)
                  + lastPart.executableCode().get().code()
                  + codeBlockDelimiters.get(1));
      newParts.set(newParts.size() - 1, newPart);
      return Content.builder().parts(newParts).role(content.role().get()).build();
    }

    // Handle the conversion of trailing code execution result parts.
    if (originalParts.size() == 1 && lastPart.codeExecutionResult().isPresent()) {
      List<Part> newParts = new ArrayList<>(originalParts);
      Part newPart =
          Part.fromText(
              executionResultDelimiters.get(0)
                  + lastPart.codeExecutionResult().get().output()
                  + executionResultDelimiters.get(1));
      newParts.set(newParts.size() - 1, newPart);
      return Content.builder().parts(newParts).role("user").build();
    }

    return content;
  }

  /**
   * Extracts the first code block from the content and truncates everything after it.
   *
   * @param contentBuilder The content builder to extract the code from and modify.
   * @param codeBlockDelimiters The list of the enclosing delimiters to identify the code blocks.
   * @return The extracted code if found.
   */
  public static Optional<String> extractCodeAndTruncateContent(
      Content.Builder contentBuilder, List<? extends List<String>> codeBlockDelimiters) {
    Content content = contentBuilder.build();
    if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
      return Optional.empty();
    }

    // Extract the code from the executable code parts if there're no associated
    // code execution result parts.
    List<Part> parts = content.parts().get();
    for (int i = 0; i < parts.size(); i++) {
      Part part = parts.get(i);
      if (part.executableCode().isPresent()
          && (i == parts.size() - 1 || parts.get(i + 1).codeExecutionResult().isEmpty())) {
        contentBuilder.parts(ImmutableList.copyOf(parts.subList(0, i + 1)));
        return part.executableCode().flatMap(ExecutableCode::code).filter(c -> !c.isEmpty());
      }
    }

    // Extract the code from the text parts.
    ImmutableList<Part> textParts =
        parts.stream()
            .filter(p -> p.text().isPresent() && !p.text().get().isEmpty())
            .collect(toImmutableList());
    if (textParts.isEmpty()) {
      return Optional.empty();
    }

    String responseText = textParts.stream().map(p -> p.text().get()).collect(joining("\n"));

    // Find the first code block.
    String leadingDelimiterPattern =
        codeBlockDelimiters.stream().map(d -> Pattern.quote(d.get(0))).collect(joining("|"));
    String trailingDelimiterPattern =
        codeBlockDelimiters.stream().map(d -> Pattern.quote(d.get(1))).collect(joining("|"));
    Pattern pattern =
        Pattern.compile(
            "(?s)(?<prefix>.*?)(?:"
                + leadingDelimiterPattern
                + ")(?<code>.*?)(?:"
                + trailingDelimiterPattern
                + ")(?<suffix>.*)");
    Matcher matcher = pattern.matcher(responseText);
    if (!matcher.find()) {
      return Optional.empty();
    }

    String codeStr = matcher.group("code");
    if (isNullOrEmpty(codeStr)) {
      return Optional.empty();
    }

    ArrayList<Part> newParts = new ArrayList<>();
    String prefix = matcher.group("prefix");
    if (prefix != null && !prefix.isEmpty()) {
      newParts.add(textParts.get(0).toBuilder().text(prefix).build());
    }
    newParts.add(buildExecutableCodePart(codeStr));
    contentBuilder.parts(newParts);
    return Optional.of(codeStr);
  }

  /** A structure that contains the result of code execution. */
  @AutoValue
  @JsonDeserialize(builder = CodeExecutionResult.Builder.class)
  public abstract static class CodeExecutionResult extends JsonBaseModel {
    /** The standard output of the code execution. */
    public abstract String stdout();

    /** The standard error of the code execution. */
    public abstract String stderr();

    /** The output files from the code execution. */
    public abstract ImmutableList<File> outputFiles();

    public static Builder builder() {
      return new AutoValue_CodeExecutionUtils_CodeExecutionResult.Builder()
          .stdout("")
          .stderr("")
          .outputFiles(ImmutableList.of());
    }

    /** Builder for {@link CodeExecutionResult}. */
    @AutoValue.Builder
    @JsonPOJOBuilder(withPrefix = "")
    public abstract static class Builder {
      @JsonCreator
      static Builder create() {
        return CodeExecutionResult.builder();
      }

      public abstract Builder stdout(String stdout);

      public abstract Builder stderr(String stderr);

      public abstract Builder outputFiles(List<File> outputFiles);

      public abstract CodeExecutionResult build();
    }
  }

  /** A structure that contains the input of code execution. */
  @AutoValue
  @JsonDeserialize(builder = CodeExecutionInput.Builder.class)
  public abstract static class CodeExecutionInput extends JsonBaseModel {
    /** The code to execute. */
    public abstract String code();

    /** The input files available to the code. */
    public abstract ImmutableList<File> inputFiles();

    /** The execution ID for the stateful code execution. */
    public abstract Optional<String> executionId();

    public static Builder builder() {
      return new AutoValue_CodeExecutionUtils_CodeExecutionInput.Builder()
          .inputFiles(ImmutableList.of())
          .executionId(Optional.empty());
    }

    /** Builder for {@link CodeExecutionInput}. */
    @AutoValue.Builder
    @JsonPOJOBuilder(withPrefix = "")
    public abstract static class Builder {
      @JsonCreator
      static Builder create() {
        return CodeExecutionInput.builder();
      }

      public abstract Builder code(String code);

      public abstract Builder inputFiles(List<File> inputFiles);

      public abstract Builder executionId(Optional<String> executionId);

      public abstract CodeExecutionInput build();
    }
  }

  /** A structure that contains a file name and its content. */
  @AutoValue
  @JsonDeserialize(builder = File.Builder.class)
  public abstract static class File extends JsonBaseModel {
    /** The name of the file with file extension (e.g., "file.csv"). */
    public abstract String name();

    /** The base64-encoded bytes of the file content. */
    public abstract String content();

    /** The mime type of the file (e.g., "image/png"). */
    public abstract String mimeType();

    public static Builder builder() {
      return new AutoValue_CodeExecutionUtils_File.Builder().mimeType("text/plain");
    }

    /** Builder for {@link File}. */
    @AutoValue.Builder
    @JsonPOJOBuilder(withPrefix = "")
    public abstract static class Builder {
      @JsonCreator
      static Builder create() {
        return File.builder();
      }

      public abstract Builder name(String name);

      public abstract Builder content(String content);

      public abstract Builder mimeType(String mimeType);

      public abstract File build();
    }
  }

  private CodeExecutionUtils() {}
}
