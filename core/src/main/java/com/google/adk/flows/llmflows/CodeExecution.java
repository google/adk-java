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

package com.google.adk.flows.llmflows;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toCollection;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.codeexecutors.BuiltInCodeExecutor;
import com.google.adk.codeexecutors.CodeExecutionUtils;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.adk.codeexecutors.CodeExecutionUtils.File;
import com.google.adk.codeexecutors.CodeExecutorContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Handles Code Execution related logic. */
public final class CodeExecution {

  private CodeExecution() {}

  public static final RequestProcessor requestProcessor = new CodeExecutionRequestProcessor();
  public static final ResponseProcessor responseProcessor = new CodeExecutionResponseProcessor();

  private record DataFileUtil(String extension, String loaderCodeTemplate) {}

  private static final ImmutableMap<String, DataFileUtil> DATA_FILE_UTIL_MAP =
      ImmutableMap.of("text/csv", new DataFileUtil(".csv", "pd.read_csv('{filename}')"));

  private static final String DATA_FILE_HELPER_LIB =
      "\"\"\"\n"
          + "import pandas as pd\n"
          + "\n"
          + "def explore_df(df: pd.DataFrame) -> None:\n"
          + "  \"\"\"Prints some information about a pandas DataFrame.\"\"\"\n"
          + "\n"
          + "  with pd.option_context(\n"
          + "      'display.max_columns', None, 'display.expand_frame_repr', False\n"
          + "  ):\n"
          + "    # Print the column names to never encounter KeyError when selecting one.\n"
          + "    df_dtypes = df.dtypes\n"
          + "\n"
          + "    # Obtain information about data types and missing values.\n"
          + "    df_nulls = (len(df) - df.isnull().sum()).apply(\n"
          + "        lambda x: f'{x} / {df.shape[0]} non-null'\n"
          + "    )\n"
          + "\n"
          + "    # Explore unique total values in columns using `.unique()`.\n"
          + "    df_unique_count = df.apply(lambda x: len(x.unique()))\n"
          + "\n"
          + "    # Explore unique values in columns using `.unique()`.\n"
          + "    df_unique = df.apply(lambda x: crop(str(list(x.unique()))))\n"
          + "\n"
          + "    df_info = pd.concat(\n"
          + "        (\n"
          + "            df_dtypes.rename('Dtype'),\n"
          + "            df_nulls.rename('Non-Null Count'),\n"
          + "            df_unique_count.rename('Unique Values Count'),\n"
          + "            df_unique.rename('Unique Values'),\n"
          + "        ),\n"
          + "        axis=1,\n"
          + "    )\n"
          + "    df_info.index.name = 'Columns'\n"
          + "    print(f\"\"\"Total rows: {df.shape[0]}\n"
          + "Total columns: {df.shape[1]}\n"
          + "\n"
          + "{df_info}\"\"\")\n"
          + "\"\"\"";

  private static class CodeExecutionRequestProcessor implements RequestProcessor {
    @Override
    public Single<RequestProcessor.RequestProcessingResult> processRequest(
        InvocationContext invocationContext, LlmRequest llmRequest) {
      if (!(invocationContext.agent() instanceof LlmAgent llmAgent)
          || llmAgent.codeExecutor().isEmpty()) {
        return Single.just(
            RequestProcessor.RequestProcessingResult.create(llmRequest, ImmutableList.of()));
      }

      if (llmAgent.codeExecutor().get() instanceof BuiltInCodeExecutor builtInCodeExecutor) {
        var llmRequestBuilder = llmRequest.toBuilder();
        builtInCodeExecutor.processLlmRequest(llmRequestBuilder);
        LlmRequest updatedLlmRequest = llmRequestBuilder.build();
        return Single.just(
            RequestProcessor.RequestProcessingResult.create(updatedLlmRequest, ImmutableList.of()));
      }

      Flowable<Event> preprocessorEvents = runPreProcessor(invocationContext, llmRequest);

      // Convert the code execution parts to text parts.
      final LlmRequest finalLlmRequest =
          llmAgent
              .codeExecutor()
              .map(
                  baseCodeExecutor -> {
                    List<String> delimiters =
                        !baseCodeExecutor.codeBlockDelimiters().isEmpty()
                            ? baseCodeExecutor.codeBlockDelimiters().get(0)
                            : ImmutableList.of("", "");
                    ImmutableList<Content> updatedContents =
                        llmRequest.contents().stream()
                            .map(
                                content ->
                                    CodeExecutionUtils.convertCodeExecutionParts(
                                        content,
                                        delimiters,
                                        baseCodeExecutor.executionResultDelimiters()))
                            .collect(toImmutableList());
                    return llmRequest.toBuilder().contents(updatedContents).build();
                  })
              .orElse(llmRequest);
      return preprocessorEvents
          .toList()
          .map(
              events ->
                  RequestProcessor.RequestProcessingResult.create(
                      finalLlmRequest, ImmutableList.copyOf(events)));
    }
  }

  private static class CodeExecutionResponseProcessor implements ResponseProcessor {
    @Override
    public Single<ResponseProcessor.ResponseProcessingResult> processResponse(
        InvocationContext invocationContext, LlmResponse llmResponse) {
      if (llmResponse.partial().orElse(false)) {
        return Single.just(
            ResponseProcessor.ResponseProcessingResult.create(
                llmResponse, ImmutableList.of(), Optional.empty()));
      }
      var llmResponseBuilder = llmResponse.toBuilder();
      return runPostProcessor(invocationContext, llmResponseBuilder)
          .toList()
          .map(
              events ->
                  ResponseProcessor.ResponseProcessingResult.create(
                      llmResponseBuilder.build(), events, Optional.empty()));
    }
  }

  private static Flowable<Event> runPreProcessor(
      InvocationContext invocationContext, LlmRequest llmRequest) {
    if (!(invocationContext.agent() instanceof LlmAgent llmAgent)) {
      return Flowable.empty();
    }

    var codeExecutorOptional = llmAgent.codeExecutor();
    if (codeExecutorOptional.isEmpty()) {
      return Flowable.empty();
    }
    var codeExecutor = codeExecutorOptional.get();

    if (codeExecutor instanceof BuiltInCodeExecutor) {
      return Flowable.empty();
    }

    if (!codeExecutor.optimizeDataFile()) {
      return Flowable.empty();
    }

    var codeExecutorContext = new CodeExecutorContext(invocationContext.session().state());

    if (codeExecutorContext.getErrorCount(invocationContext.invocationId())
        >= codeExecutor.errorRetryAttempts()) {
      return Flowable.empty();
    }

    List<File> allInputFiles = extractAndReplaceInlineFiles(codeExecutorContext, llmRequest);

    var processedFileNames = new HashSet<>(codeExecutorContext.getProcessedFileNames());
    ImmutableList<File> filesToProcess =
        allInputFiles.stream()
            .filter(f -> !processedFileNames.contains(f.name()))
            .collect(toImmutableList());

    return Flowable.fromIterable(filesToProcess)
        .concatMap(
            file -> {
              Optional<String> codeStrOptional = getDataFilePreprocessingCode(file);
              if (codeStrOptional.isEmpty()) {
                return Flowable.empty();
              }
              String codeStr = codeStrOptional.get();

              Content codeContent =
                  Content.builder()
                      .role("model")
                      .parts(
                          Part.fromText(String.format("Processing input file: `%s`", file.name())),
                          CodeExecutionUtils.buildExecutableCodePart(codeStr))
                      .build();

              llmRequest.contents().add(codeContent);
              Event codeEvent =
                  Event.builder()
                      .invocationId(invocationContext.invocationId())
                      .author(llmAgent.name())
                      .content(Optional.of(codeContent))
                      .build();

              return Flowable.defer(
                      () -> {
                        CodeExecutionResult codeExecutionResult =
                            codeExecutor.executeCode(
                                invocationContext,
                                CodeExecutionInput.builder()
                                    .code(codeStr)
                                    .inputFiles(ImmutableList.of(file))
                                    .executionId(
                                        getOrSetExecutionId(invocationContext, codeExecutorContext))
                                    .build());

                        codeExecutorContext.updateCodeExecutionResult(
                            invocationContext.invocationId(),
                            codeStr,
                            codeExecutionResult.stdout(),
                            codeExecutionResult.stderr());
                        codeExecutorContext.addProcessedFileNames(ImmutableList.of(file.name()));

                        return postProcessCodeExecutionResult(
                                invocationContext, codeExecutorContext, codeExecutionResult)
                            .toFlowable();
                      })
                  .doOnNext(
                      executionResultEvent ->
                          llmRequest
                              .contents()
                              .add(
                                  executionResultEvent
                                      .content()
                                      .orElseGet(() -> Content.builder().build())))
                  .map(executionResultEvent -> ImmutableList.of(codeEvent, executionResultEvent))
                  .flatMap(Flowable::fromIterable);
            });
  }

  private static Flowable<Event> runPostProcessor(
      InvocationContext invocationContext, LlmResponse.Builder llmResponseBuilder) {
    LlmResponse llmResponse = llmResponseBuilder.build();
    if (!(invocationContext.agent() instanceof LlmAgent llmAgent)) {
      return Flowable.empty();
    }
    var codeExecutorOptional = llmAgent.codeExecutor();
    if (codeExecutorOptional.isEmpty()) {
      return Flowable.empty();
    }
    var codeExecutor = codeExecutorOptional.get();
    if (llmResponse.content().isEmpty()) {
      return Flowable.empty();
    }
    if (codeExecutor instanceof BuiltInCodeExecutor) {
      return Flowable.empty();
    }

    var codeExecutorContext = new CodeExecutorContext(invocationContext.session().state());
    if (codeExecutorContext.getErrorCount(invocationContext.invocationId())
        >= codeExecutor.errorRetryAttempts()) {
      return Flowable.empty();
    }

    Content responseContent = llmResponse.content().get();
    Content.Builder responseContentBuilder = responseContent.toBuilder();
    Optional<String> codeStrOptional =
        CodeExecutionUtils.extractCodeAndTruncateContent(
            responseContentBuilder, codeExecutor.codeBlockDelimiters());

    if (codeStrOptional.isEmpty()) {
      return Flowable.empty();
    }
    String codeStr = codeStrOptional.get();
    responseContent = responseContentBuilder.build();
    llmResponseBuilder.content(Optional.empty());

    Event codeEvent =
        Event.builder()
            .invocationId(invocationContext.invocationId())
            .author(llmAgent.name())
            .content(Optional.of(responseContent))
            .actions(EventActions.builder().build())
            .build();

    return Flowable.defer(
            () -> {
              CodeExecutionResult codeExecutionResult =
                  codeExecutor.executeCode(
                      invocationContext,
                      CodeExecutionInput.builder()
                          .code(codeStr)
                          .inputFiles(codeExecutorContext.getInputFiles())
                          .executionId(getOrSetExecutionId(invocationContext, codeExecutorContext))
                          .build());
              codeExecutorContext.updateCodeExecutionResult(
                  invocationContext.invocationId(),
                  codeStr,
                  codeExecutionResult.stdout(),
                  codeExecutionResult.stderr());
              return postProcessCodeExecutionResult(
                      invocationContext, codeExecutorContext, codeExecutionResult)
                  .toFlowable();
            })
        .map(executionResultEvent -> ImmutableList.of(codeEvent, executionResultEvent))
        .flatMap(Flowable::fromIterable);
  }

  private static List<File> extractAndReplaceInlineFiles(
      CodeExecutorContext codeExecutorContext, LlmRequest llmRequest) {
    List<File> allInputFiles = new ArrayList<>(codeExecutorContext.getInputFiles());
    Set<String> savedFileNames =
        allInputFiles.stream().map(File::name).collect(toCollection(HashSet::new));

    for (int i = 0; i < llmRequest.contents().size(); i++) {
      Content content = llmRequest.contents().get(i);
      if (content.role().isEmpty()
          || !Objects.equals(content.role().get(), "user")
          || content.parts().isEmpty()) {
        continue;
      }

      List<Part> newParts = new ArrayList<>(content.parts().get());
      boolean modified = false;

      for (int j = 0; j < newParts.size(); j++) {
        Part part = newParts.get(j);
        if (part.inlineData().isEmpty()
            || part.inlineData().get().mimeType().isEmpty()
            || !DATA_FILE_UTIL_MAP.containsKey(part.inlineData().get().mimeType().get())) {
          continue;
        }
        modified = true;
        String mimeType = part.inlineData().get().mimeType().get();
        String fileName =
            String.format("data_%d_%d", i + 1, j + 1)
                + DATA_FILE_UTIL_MAP.get(mimeType).extension();
        newParts.set(j, Part.fromText(String.format("\nAvailable file: `%s`\n", fileName)));

        File file =
            File.builder()
                .name(fileName)
                .content(
                    new String(
                        Base64.getEncoder().encode(part.inlineData().get().data().get()), UTF_8))
                .mimeType(mimeType)
                .build();

        if (!savedFileNames.contains(fileName)) {
          codeExecutorContext.addInputFiles(ImmutableList.of(file));
          allInputFiles.add(file);
          savedFileNames.add(fileName);
        }
      }

      if (modified) {
        Content newContent = content.toBuilder().parts(newParts).build();
        llmRequest.contents().set(i, newContent);
      }
    }
    return allInputFiles;
  }

  private static Optional<String> getOrSetExecutionId(
      InvocationContext invocationContext, CodeExecutorContext codeExecutorContext) {
    if (!(invocationContext.agent() instanceof LlmAgent llmAgent)
        || llmAgent.codeExecutor().isEmpty()
        || !llmAgent.codeExecutor().get().stateful()) {
      return Optional.empty();
    }

    Optional<String> executionId = codeExecutorContext.getExecutionId();
    if (executionId.isEmpty()) {
      String newExecutionId = invocationContext.session().id();
      codeExecutorContext.setExecutionId(newExecutionId);
      return Optional.of(newExecutionId);
    }
    return executionId;
  }

  private static Single<Event> postProcessCodeExecutionResult(
      InvocationContext invocationContext,
      CodeExecutorContext codeExecutorContext,
      CodeExecutionResult codeExecutionResult) {
    if (invocationContext.artifactService() == null) {
      return Single.error(new IllegalStateException("Artifact service is not initialized."));
    }

    Content resultContent =
        Content.builder()
            .role("model")
            .parts(CodeExecutionUtils.buildCodeExecutionResultPart(codeExecutionResult))
            .build();

    EventActions.Builder eventActionsBuilder =
        EventActions.builder()
            .stateDelta(new ConcurrentHashMap<>(codeExecutorContext.getStateDelta()));

    if (codeExecutionResult.stderr() != null && !codeExecutionResult.stderr().isEmpty()) {
      codeExecutorContext.incrementErrorCount(invocationContext.invocationId());
    } else {
      codeExecutorContext.resetErrorCount(invocationContext.invocationId());
    }

    return Flowable.fromIterable(codeExecutionResult.outputFiles())
        .concatMapSingle(
            outputFile ->
                invocationContext
                    .artifactService()
                    .saveArtifact(
                        invocationContext.appName(),
                        invocationContext.userId(),
                        invocationContext.session().id(),
                        outputFile.name(),
                        Part.fromBytes(
                            Base64.getDecoder().decode(outputFile.content()),
                            outputFile.mimeType())))
        .toList()
        .map(
            versions -> {
              ConcurrentMap<String, Integer> artifactDelta = new ConcurrentHashMap<>();
              for (int i = 0; i < versions.size(); i++) {
                artifactDelta.put(codeExecutionResult.outputFiles().get(i).name(), versions.get(i));
              }
              eventActionsBuilder.artifactDelta(artifactDelta);
              return Event.builder()
                  .invocationId(invocationContext.invocationId())
                  .author(invocationContext.agent().name())
                  .content(Optional.of(resultContent))
                  .actions(eventActionsBuilder.build())
                  .build();
            });
  }

  private static Optional<String> getDataFilePreprocessingCode(File file) {
    if (!DATA_FILE_UTIL_MAP.containsKey(file.mimeType())) {
      return Optional.empty();
    }

    String varName = getNormalizedFileName(file.name());
    String loaderCode =
        DATA_FILE_UTIL_MAP
            .get(file.mimeType())
            .loaderCodeTemplate()
            .replace("{filename}", file.name());

    return Optional.of(
        String.format(
            "\"\"\"\n"
                + "%s\n"
                + "\n"
                + "# Load the dataframe.\n"
                + "%s = %s\n"
                + "\n"
                + "# Use `explore_df` to guide my analysis.\n"
                + "explore_df(%s)\n"
                + "\"\"\"",
            DATA_FILE_HELPER_LIB, varName, loaderCode, varName));
  }

  private static String getNormalizedFileName(String fileName) {
    String varName = Path.of(fileName).getFileName().toString();
    int dotIndex = varName.lastIndexOf('.');
    if (dotIndex != -1) {
      varName = varName.substring(0, dotIndex);
    }
    varName = varName.replaceAll("[^a-zA-Z0-9_]", "_");
    if (!varName.isEmpty() && Character.isDigit(varName.charAt(0))) {
      varName = "_" + varName;
    }
    return varName;
  }
}
