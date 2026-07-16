package com.google.adk.models.factory;

import com.anthropic.bedrock.backends.BedrockBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.google.adk.models.Claude;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Factory class for creating Claude models backed by AWS Bedrock. Provides convenient methods to
 * create Claude instances with AWS Bedrock integration.
 *
 * @author Akshaya Rawat
 * @version 1.0
 * @since 2025-07-15
 */
public class ClaudeBedrock {
  public static final String DEFAULT_BEDROCK_CLAUDE_MODEL_ID =
      "apac.anthropic.claude-3-7-sonnet-20250219-v1:0";
  private static final Logger log = LoggerFactory.getLogger(ClaudeBedrock.class);

  private static String getDefaultBedrockClaudeModelId() {
    return Optional.ofNullable(System.getenv("BEDROCK_CLAUDE_MODEL_ID"))
        .orElse(DEFAULT_BEDROCK_CLAUDE_MODEL_ID);
  }

  /**
   * Creates a Claude instance using the default Bedrock model ID.
   *
   * @return a new Claude instance configured with AWS Bedrock
   */
  public static Claude create() {
    return create(getDefaultBedrockClaudeModelId());
  }

  /**
   * Creates a Claude instance with the specified model ID.
   *
   * @param modelId the Bedrock model ID to use
   * @return a new Claude instance configured with AWS Bedrock
   */
  public static Claude create(String modelId) {
    return create(modelId, null);
  }

  /**
   * Creates a Claude instance with the specified model ID and AWS region.
   *
   * @param _modelId the Bedrock model ID to use (null uses default)
   * @param _region the AWS region to use (null uses environment variable or default)
   * @return a new Claude instance configured with AWS Bedrock
   */
  public static Claude create(String _modelId, String _region) {
    String modelId = Optional.ofNullable(_modelId).orElse(getDefaultBedrockClaudeModelId());
    Region region = getRegion(_region);
    log.info("Using AWS Bedrock model: {}", modelId);
    return getClaudeFromAWSBedrock(modelId, region);
  }

  @NotNull
  private static Claude getClaudeFromAWSBedrock(String modelId, Region region) {
    try {
      AwsCredentialsProvider awsCredentialsProvider =
          DefaultCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build();
      awsCredentialsProvider.resolveCredentials();
      log.debug("AWS credentials validated successfully");
      log.debug("Creating AWS Bedrock client");
      BedrockBackend.Builder builder =
          BedrockBackend.builder().awsCredentialsProvider(awsCredentialsProvider);
      BedrockBackend bedrockBackend =
          Optional.ofNullable(region).map(r -> builder.region(r).build()).orElse(builder.build());
      AnthropicClient anthropicClient =
          AnthropicOkHttpClient.builder().backend(bedrockBackend).build();
      return new Claude(modelId, anthropicClient);
    } catch (Exception e) {
      log.error("Failed to resolve AWS credentials: {}", e.getMessage());
      throw new IllegalStateException("AWS credentials not available", e);
    }
  }

  private static Region getRegion(String _region) {
    String regionStr = Optional.ofNullable(_region).orElse(System.getenv("AWS_REGION"));
    Region region = Optional.ofNullable(regionStr).map(Region::of).orElse(null);
    log.info("Using AWS region: {}", regionStr);
    return region;
  }
}
