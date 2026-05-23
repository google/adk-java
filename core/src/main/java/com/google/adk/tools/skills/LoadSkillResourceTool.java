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

package com.google.adk.tools.skills;

import static com.google.adk.tools.skills.SkillToolset.createErrorResponse;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.net.URLConnection.guessContentTypeFromName;
import static java.net.URLConnection.guessContentTypeFromStream;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.models.LlmRequest;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteSource;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Tool to load resources (references, assets, or scripts) from a skill. */
final class LoadSkillResourceTool extends BaseTool {

  private static final ImmutableSet<String> EXTRA_TEXT_MIME_TYPES =
      ImmutableSet.of(
          // go/keep-sorted start
          "application/json",
          "application/x-python",
          "application/x-sh",
          "application/x-shar",
          "application/x-shellscript",
          "application/xml",
          "application/yaml"
          // go/keep-sorted end
          );
  private static final String BINARY_FILE_DETECTED_MSG =
      "Binary file detected. The content has been included in the next part of the function"
          + " response for you to analyze.";
  private static final String SKILL_NAME = "skill_name";
  private static final String FILE_PATH = "file_path";
  private static final String CONTENT = "content";
  private static final String MIME_TYPE = "mime_type";

  private final SkillSource skillSource;

  LoadSkillResourceTool(SkillSource skillSource) {
    super(
        "load_skill_resource",
        "Loads a resource file (from references/, assets/, or scripts/) from within a skill.");
    this.skillSource = skillSource;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(name())
            .description(description())
            .parameters(
                Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(
                        ImmutableMap.of(
                            SKILL_NAME,
                            Schema.builder()
                                .type(Type.Known.STRING)
                                .description("The name of the skill.")
                                .build(),
                            FILE_PATH,
                            Schema.builder()
                                .type(Type.Known.STRING)
                                .description(
                                    "The relative path to the resource (e.g.,"
                                        + " 'references/my_doc.md', 'assets/template.txt',"
                                        + " or 'scripts/setup.sh').")
                                .build()))
                    .required(ImmutableList.of(SKILL_NAME, FILE_PATH))
                    .build())
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    String skillName = (String) args.get(SKILL_NAME);
    String resourcePath = (String) args.get(FILE_PATH);

    if (Strings.isNullOrEmpty(skillName)) {
      return createErrorResponse("Skill name is required.", "MISSING_SKILL_NAME");
    }
    if (Strings.isNullOrEmpty(resourcePath)) {
      return createErrorResponse("Resource path is required.", "MISSING_RESOURCE_PATH");
    }
    if (!resourcePath.startsWith("references/")
        && !resourcePath.startsWith("assets/")
        && !resourcePath.startsWith("scripts/")) {
      return createErrorResponse(
          "Path must start with 'references/', 'assets/', or 'scripts/'.", "INVALID_RESOURCE_PATH");
    }

    return skillSource
        .loadResource(skillName, resourcePath)
        .<Map<String, Object>>map(
            contentSource -> createResult(skillName, resourcePath, contentSource))
        .onErrorResumeNext(SkillToolset::createErrorResponse);
  }

  private boolean hasBinaryContentResponse(FunctionResponse functionResponse) {
    return functionResponse
        .response()
        .filter(
            resp ->
                resp.containsKey(SKILL_NAME)
                    && resp.containsKey(MIME_TYPE)
                    && resp.get(CONTENT) instanceof byte[])
        .isPresent();
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    return super.processLlmRequest(llmRequestBuilder, toolContext)
        .andThen(
            Completable.fromRunnable(
                () -> {
                  List<Content> contents = new ArrayList<>(llmRequestBuilder.build().contents());
                  if (contents.isEmpty()) {
                    return;
                  }

                  Content lastContent = Iterables.getLast(contents);
                  List<Part> parts = lastContent.parts().orElse(ImmutableList.of());

                  // Extract raw binary content into a dedicated binary Part
                  ImmutableList<Part> updatedParts =
                      parts.stream().flatMap(this::processPart).collect(toImmutableList());

                  if (!updatedParts.isEmpty()) {
                    contents.set(
                        contents.size() - 1, lastContent.toBuilder().parts(updatedParts).build());
                    llmRequestBuilder.contents(contents);
                  }
                }));
  }

  /**
   * Processes a {@link Part} to extract raw binary content from a function response.
   *
   * <p>If the part is a function response from this tool containing binary data, it returns a
   * stream containing the updated function response part (with a placeholder message) and a new
   * part containing the raw binary data. Otherwise, it returns a stream of the original {@link
   * Part}.
   *
   * @param part the {@link Part} to process
   * @return a stream containing the processed parts
   */
  private Stream<Part> processPart(Part part) {
    FunctionResponse binaryFunctionResponse =
        part.functionResponse()
            .filter(funcResp -> funcResp.name().orElse("").equals(name()))
            .filter(this::hasBinaryContentResponse)
            .orElse(null);

    if (binaryFunctionResponse == null) {
      return Stream.of(part);
    }

    return binaryFunctionResponse.response().stream()
        .flatMap(
            response -> {
              Map<String, Object> newResponse = new HashMap<>(response);

              String mimeType = newResponse.remove(MIME_TYPE).toString();
              byte[] binaryContent =
                  (byte[]) newResponse.replace(CONTENT, BINARY_FILE_DETECTED_MSG);

              Part updatedPart =
                  part.toBuilder()
                      .functionResponse(binaryFunctionResponse.toBuilder().response(newResponse))
                      .build();
              Part binaryPart = Part.fromBytes(binaryContent, mimeType);

              return Stream.of(updatedPart, binaryPart);
            });
  }

  private ImmutableMap<String, Object> createResult(
      String skillName, String resourcePath, ByteSource contentSource) throws IOException {
    byte[] bytes = contentSource.read();
    // Special handling of shell script as the guessContentTypeFromName would return
    // application/x-shar
    String contentType =
        resourcePath.endsWith(".sh") || resourcePath.endsWith(".bash")
            ? "application/x-sh"
            : guessContentTypeFromName(resourcePath);
    if (contentType == null) {
      contentType = guessContentTypeFromStream(new ByteArrayInputStream(bytes));
    }
    if (contentType == null) {
      contentType = "application/octet-stream";
    }
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
    builder.put(SKILL_NAME, skillName).put(FILE_PATH, resourcePath).put(MIME_TYPE, contentType);

    if (contentType.startsWith("text/") || EXTRA_TEXT_MIME_TYPES.contains(contentType)) {
      builder.put(CONTENT, new String(bytes, UTF_8));
    } else {
      builder.put(CONTENT, bytes);
    }
    return builder.buildOrThrow();
  }
}
