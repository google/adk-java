package com.google.adk.models.demo.structured;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.genai.types.Schema;
import jakarta.inject.Provider;

public final class MovieExtractorProvider implements Provider<LlmAgent> {

  private static final String INSTRUCTION =
      """
      You are a data extraction assistant.
      Extract the structured movie facts
      from the text provided. Respond only
      with the required JSON fields - no
      extra commentary.
      """;

  public MovieExtractorProvider(String model, Schema schema) {
    this.model = checkNotNull(model, "model");
    this.schema = checkNotNull(schema, "schema");
  }

  private final String model;
  private final Schema schema;

  @Override
  public LlmAgent get() {
    return LlmAgent.builder()
        .name("movie-extractor") //
        .model(model) //
        .description("Data Extraction Assistant") //
        .instruction(INSTRUCTION) //
        .outputSchema(schema) //
        .outputKey("movie_data") //
        .build();
  }
}
