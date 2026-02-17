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

package com.google.adk.codeexecutors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.InvocationContext;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.adk.codeexecutors.CodeExecutionUtils.File;
import com.google.cloud.aiplatform.v1beta1.ExecuteExtensionRequest;
import com.google.cloud.aiplatform.v1beta1.ExecuteExtensionResponse;
import com.google.cloud.aiplatform.v1beta1.ExtensionExecutionServiceClient;
import com.google.cloud.aiplatform.v1beta1.ExtensionExecutionServiceSettings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A code executor that uses Vertex Code Interpreter Extension to execute code.
 *
 * <p>Attributes: resourceName: If set, load the existing resource name of the code interpreter
 * extension instead of creating a new one. Format:
 * projects/123/locations/us-central1/extensions/456
 *
 * <p>Follow https://cloud.google.com/vertex-ai/generative-ai/docs/extensions/code-interpreter for
 * setup.
 */
public final class VertexAiCodeExecutor extends BaseCodeExecutor {
  private static final Logger logger = LoggerFactory.getLogger(VertexAiCodeExecutor.class);

  private static final ImmutableList<String> SUPPORTED_IMAGE_TYPES =
      ImmutableList.of("png", "jpg", "jpeg");
  private static final ImmutableList<String> SUPPORTED_DATA_FILE_TYPES = ImmutableList.of("csv");

  private static final String IMPORTED_LIBRARIES =
      "import io\n"
          + "import math\n"
          + "import re\n"
          + "\n"
          + "import matplotlib.pyplot as plt\n"
          + "import numpy as np\n"
          + "import pandas as pd\n"
          + "import scipy\n"
          + "\n"
          + "def crop(s: str, max_chars: int = 64) -> str:\n"
          + "  \"\"\"Crops a string to max_chars characters.\"\"\"\n"
          + "  return s[: max_chars - 3] + '...' if len(s) > max_chars else s\n"
          + "\n"
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
          + "{df_info}\"\"\")";

  private final String resourceName;
  private ExtensionExecutionServiceClient codeInterpreterExtension;
  private final Object extensionClientLock = new Object();

  /**
   * Initializes the VertexAiCodeExecutor.
   *
   * @param resourceName If set, load the existing resource name of the code interpreter extension
   *     instead of creating a new one. Format: projects/123/locations/us-central1/extensions/456
   */
  public VertexAiCodeExecutor(String resourceName) {
    String resolvedResourceName = resourceName;
    if (resolvedResourceName == null || resolvedResourceName.isEmpty()) {
      resolvedResourceName = System.getenv("CODE_INTERPRETER_EXTENSION_NAME");
    }

    if (resolvedResourceName == null || resolvedResourceName.isEmpty()) {
      logger.warn(
          "No resource name found for Vertex AI Code Interpreter. It will not be available.");
      this.resourceName = null;
    } else {
      this.resourceName = resolvedResourceName;
    }
  }

  @Override
  public CodeExecutionResult executeCode(
      InvocationContext invocationContext, CodeExecutionInput codeExecutionInput) {
    // Execute the code.
    Map<String, Object> codeExecutionResult =
        executeCodeInterpreter(
            getCodeWithImports(codeExecutionInput.code()),
            codeExecutionInput.inputFiles(),
            codeExecutionInput.executionId());

    // Save output file as artifacts.
    List<File> savedFiles = new ArrayList<>();
    if (codeExecutionResult.containsKey("output_files")) {
      @SuppressWarnings("unchecked")
      List<Map<String, String>> outputFiles =
          (List<Map<String, String>>) codeExecutionResult.get("output_files");
      for (Map<String, String> outputFile : outputFiles) {
        String fileName = outputFile.get("name");
        String content = outputFile.get("contents"); // This is a base64 string.
        String fileType = fileName.substring(fileName.lastIndexOf('.') + 1);
        String mimeType;
        if (SUPPORTED_IMAGE_TYPES.contains(fileType)) {
          mimeType = "image/" + fileType;
        } else if (SUPPORTED_DATA_FILE_TYPES.contains(fileType)) {
          mimeType = "text/" + fileType;
        } else {
          mimeType = URLConnection.guessContentTypeFromName(fileName);
        }
        savedFiles.add(File.builder().name(fileName).content(content).mimeType(mimeType).build());
      }
    }

    // Collect the final result.
    return CodeExecutionResult.builder()
        .stdout((String) codeExecutionResult.getOrDefault("execution_result", ""))
        .stderr((String) codeExecutionResult.getOrDefault("execution_error", ""))
        .outputFiles(savedFiles)
        .build();
  }

  private Map<String, Object> executeCodeInterpreter(
      String code, List<File> inputFiles, Optional<String> sessionId) {
    ExtensionExecutionServiceClient codeInterpreterExtension = getCodeInterpreterExtension();
    if (codeInterpreterExtension == null) {
      logger.warn("Vertex AI Code Interpreter execution is not available. Returning empty result.");
      return ImmutableMap.of(
          "execution_result", "", "execution_error", "", "output_files", new ArrayList<>());
    }

    // Build operationParams
    Struct.Builder paramsBuilder = Struct.newBuilder();
    paramsBuilder.putFields("query", Value.newBuilder().setStringValue(code).build());
    if (inputFiles != null && !inputFiles.isEmpty()) {
      ListValue.Builder listBuilder = ListValue.newBuilder();
      for (File f : inputFiles) {
        Struct.Builder fileStructBuilder = Struct.newBuilder();
        fileStructBuilder.putFields("name", Value.newBuilder().setStringValue(f.name()).build());
        fileStructBuilder.putFields(
            "contents", Value.newBuilder().setStringValue(f.content()).build());
        listBuilder.addValues(Value.newBuilder().setStructValue(fileStructBuilder.build()));
      }
      paramsBuilder.putFields(
          "files", Value.newBuilder().setListValue(listBuilder.build()).build());
    }
    sessionId.ifPresent(
        s -> paramsBuilder.putFields("session_id", Value.newBuilder().setStringValue(s).build()));

    ExecuteExtensionRequest request =
        ExecuteExtensionRequest.newBuilder()
            .setName(this.resourceName)
            .setOperationId("generate_and_execute")
            .setOperationParams(paramsBuilder.build())
            .build();

    ExecuteExtensionResponse response = codeInterpreterExtension.executeExtension(request);
    String jsonOutput = response.getContent();
    if (jsonOutput == null || jsonOutput.isEmpty()) {
      return ImmutableMap.of(
          "execution_result", "", "execution_error", "", "output_files", new ArrayList<>());
    }

    try {
      ObjectMapper mapper = new ObjectMapper();
      return mapper.readValue(jsonOutput, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      logger.error("Failed to parse JSON from code interpreter: " + jsonOutput, e);
      return ImmutableMap.of(
          "execution_result",
          "",
          "execution_error",
          "Failed to parse extension response: " + e.getMessage(),
          "output_files",
          new ArrayList<>());
    }
  }

  private ExtensionExecutionServiceClient getCodeInterpreterExtension() {
    if (this.resourceName == null) {
      return null;
    }
    synchronized (extensionClientLock) {
      if (this.codeInterpreterExtension == null) {
        try {
          String[] parts = this.resourceName.split("/");
          if (parts.length < 4 || !parts[2].equals("locations")) {
            throw new IllegalArgumentException(
                "Invalid resource name format: " + this.resourceName);
          }
          String location = parts[3];
          String endpoint = String.format("%s-aiplatform.googleapis.com:443", location);
          ExtensionExecutionServiceSettings settings =
              ExtensionExecutionServiceSettings.newBuilder().setEndpoint(endpoint).build();
          this.codeInterpreterExtension = ExtensionExecutionServiceClient.create(settings);
        } catch (IOException e) {
          throw new IllegalStateException("Failed to create ExtensionExecutionServiceClient", e);
        }
      }
      return this.codeInterpreterExtension;
    }
  }

  private String getCodeWithImports(String code) {
    return String.format("%s\n\n%s", IMPORTED_LIBRARIES, code);
  }
}
