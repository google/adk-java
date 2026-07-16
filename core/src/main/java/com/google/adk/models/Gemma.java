package com.google.adk.models;

import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.Version;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gemma extends Gemini implements GemmaFunctionCallingSupport {
  private static final Logger logger = LoggerFactory.getLogger(Gemma.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final ImmutableMap<String, String> TRACKING_HEADERS;

  static {
    String frameworkLabel = "google-adk/" + Version.JAVA_ADK_VERSION;
    String languageLabel = "gl-java/" + JAVA_VERSION.value();
    String versionHeaderValue = String.format("%s %s", frameworkLabel, languageLabel);

    TRACKING_HEADERS =
        ImmutableMap.of(
            "x-goog-api-client", versionHeaderValue,
            "user-agent", versionHeaderValue);
  }

  public Gemma(String modelName, Client apiClient) {
    super(modelName, apiClient);
  }

  public Gemma(String modelName, String apiKey) {
    super(modelName, apiKey);
  }

  public Gemma(String modelName, VertexCredentials vertexCredentials) {
    super(modelName, vertexCredentials);
  }

  /**
   * Returns a new Builder instance for constructing Gemma objects.
   *
   * @return A new {@link Builder}.
   */

  /** Builder for {@link Gemma}. */
  public static class Builder {
    private String modelName;
    private Client apiClient;
    private String apiKey;
    private VertexCredentials vertexCredentials;

    private Builder() {}

    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    public Builder apiClient(Client apiClient) {
      this.apiClient = apiClient;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder vertexCredentials(VertexCredentials vertexCredentials) {
      this.vertexCredentials = vertexCredentials;
      return this;
    }

    public Gemma build() {
      if (apiClient != null) {
        return new Gemma(modelName, apiClient);
      } else if (apiKey != null) {
        return new Gemma(modelName, apiKey);
      } else if (vertexCredentials != null) {
        return new Gemma(modelName, vertexCredentials);
      } else {
        return new Gemma(
            modelName,
            Client.builder()
                .httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
                .build());
      }
    }
  }

  public List<String> supportedModels() {
    return ImmutableList.of("gemma-3.*");
  }

  protected LlmRequest preprocessRequest(LlmRequest llmRequest) {
    llmRequest = moveFunctionCallsIntoSystemInstruction(llmRequest, objectMapper, logger);

    Optional<GenerateContentConfig> configOpt = llmRequest.config();
    if (configOpt.isPresent() && configOpt.get().systemInstruction().isPresent()) {
      Content systemInstruction = configOpt.get().systemInstruction().get();
      String instructionText = "";

      if (systemInstruction.parts().isPresent() && !systemInstruction.parts().get().isEmpty()) {
        instructionText = systemInstruction.parts().get().get(0).text().orElse("");
      }

      List<Content> contents = new ArrayList<>(llmRequest.contents());
      Content instructionContent =
          Content.builder()
              .role("user")
              .parts(ImmutableList.of(Part.fromText(instructionText)))
              .build();

      if (!contents.isEmpty()) {
        if (!contents.get(0).equals(instructionContent)) {
          contents.add(0, instructionContent);
          llmRequest = llmRequest.toBuilder().contents(contents).build();
        }
      } else {
        llmRequest = llmRequest.toBuilder().contents(ImmutableList.of(instructionContent)).build();
      }

      GenerateContentConfig newConfig =
          configOpt.get().toBuilder()
              .systemInstruction(Content.builder().parts(ImmutableList.of()).build())
              .build();
      llmRequest = llmRequest.toBuilder().config(newConfig).build();
    }
    return llmRequest;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (!llmRequest.model().orElse("").startsWith("gemma-")) {
      throw new IllegalArgumentException(
          "Requesting a non-Gemma model ("
              + llmRequest.model().orElse("")
              + ") with the Gemma LLM is not supported.");
    }
    llmRequest = preprocessRequest(llmRequest);
    return super.generateContent(llmRequest, stream)
        .map(resp -> extractFunctionCallsFromResponse(resp, objectMapper, logger));
  }
}
