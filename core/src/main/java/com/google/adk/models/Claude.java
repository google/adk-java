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

package com.google.adk.models;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.Properties;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.MessageParam.Role;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ContextCacheConfig;
import com.google.common.base.Ascii;
import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.ThinkingConfig;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the Claude Generative AI model by Anthropic.
 *
 * <p>Live connections are not supported for Claude.
 */
public class Claude extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(Claude.class);

  private static final int DEFAULT_MAX_TOKENS = 8192;

  // JSON Schema keywords traversed by updateTypeString, grouped by the shape of their value.
  // Keywords whose value is a map of named sub-schemas (e.g. "properties": {"a": {...}}).
  private static final ImmutableList<String> NESTED_SCHEMA_MAP_KEYWORDS =
      ImmutableList.of("$defs", "defs", "dependentSchemas", "patternProperties", "properties");
  // Keywords whose value is a single sub-schema (e.g. "items": {...}).
  private static final ImmutableList<String> NESTED_SCHEMA_KEYWORDS =
      ImmutableList.of(
          "additionalProperties",
          "additional_properties",
          "contains",
          "else",
          "if",
          "items",
          "not",
          "propertyNames",
          "then",
          "unevaluatedProperties");
  // Keywords whose value is a list of sub-schemas (e.g. "anyOf": [{...}, {...}]).
  private static final ImmutableList<String> NESTED_SCHEMA_LIST_KEYWORDS =
      ImmutableList.of("allOf", "all_of", "anyOf", "any_of", "oneOf", "one_of", "prefixItems");

  // A Vertex AI resource name for a Claude model; group 1 is the model id.
  private static final Pattern VERTEX_RESOURCE_NAME =
      Pattern.compile(
          "^projects/[^/]+/locations/[^/]+/(?:publishers/anthropic/models|endpoints)/([^/:]+)");

  // The pattern Claude requires of tool_use ids.
  private static final Pattern VALID_TOOL_USE_ID = Pattern.compile("[a-zA-Z0-9_-]+");

  private static final ImmutableSet<String> IMAGE_MEDIA_TYPES =
      ImmutableSet.of("image/jpeg", "image/png", "image/gif", "image/webp");
  private static final String PDF_MEDIA_TYPE = "application/pdf";

  // Stop reasons that can cut a tool_use block off before its input is complete.
  private static final ImmutableSet<StopReason.Value> TRUNCATING_STOP_REASONS =
      ImmutableSet.of(StopReason.Value.MAX_TOKENS, StopReason.Value.MODEL_CONTEXT_WINDOW_EXCEEDED);
  private static final String TRUNCATED_TOOL_USE_MESSAGE =
      "The response was cut off by the max_tokens or context window limit before a tool call or"
          + " structured output was complete, so it was not returned. Increase maxOutputTokens or"
          + " shorten the context and retry.";

  // The tool a requested response schema is sent as; see OutputSchemaTool.
  private static final String OUTPUT_SCHEMA_TOOL_NAME = "structured_output";
  private static final String OUTPUT_SCHEMA_TOOL_DESCRIPTION =
      "Provide your final answer. Call this tool exactly once, with your whole answer in the"
          + " structure its input describes.";
  private static final String OUTPUT_SCHEMA_VALUE_KEY = "response";

  private final int maxTokens;
  private final OutputConfig.@Nullable Effort effort;
  private final AnthropicClient anthropicClient;
  // Each warns once per model rather than on every request.
  private final AtomicBoolean warnedThinkingLevel = new AtomicBoolean();
  private final AtomicBoolean warnedMissingThinkingBudget = new AtomicBoolean();
  private final AtomicBoolean warnedIgnoredSampling = new AtomicBoolean();

  /**
   * Constructs a new Claude instance. Requests sent through {@code anthropicClient} carry ADK's
   * usage-tracking headers, added to the ones the client already sends.
   *
   * @param modelName The name of the Claude model to use (e.g., "claude-3-opus-20240229").
   * @param anthropicClient The Anthropic API client instance.
   */
  public Claude(String modelName, AnthropicClient anthropicClient) {
    this(modelName, anthropicClient, DEFAULT_MAX_TOKENS);
  }

  /**
   * Constructs a new Claude instance with a default for the maximum number of tokens to generate.
   *
   * @param modelName The name of the Claude model to use.
   * @param anthropicClient The Anthropic API client instance.
   * @param maxTokens The {@code max_tokens} sent when the request config sets no maxOutputTokens.
   */
  public Claude(String modelName, AnthropicClient anthropicClient, int maxTokens) {
    this(modelName, anthropicClient, maxTokens, null);
  }

  private Claude(
      String modelName,
      AnthropicClient anthropicClient,
      int maxTokens,
      OutputConfig.@Nullable Effort effort) {
    super(modelName);
    this.anthropicClient = withTrackingHeaders(anthropicClient);
    this.maxTokens = maxTokens;
    this.effort = effort;
  }

  /**
   * Returns a builder for {@link Claude}. Use it for options the constructors don't take, such as
   * reasoning effort.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link Claude}. */
  public static final class Builder {
    private @Nullable String modelName;
    private @Nullable AnthropicClient anthropicClient;
    private int maxTokens = DEFAULT_MAX_TOKENS;
    private OutputConfig.@Nullable Effort effort;

    private Builder() {}

    /**
     * Sets the name of the Claude model to use. Required.
     *
     * @param modelName The model name (e.g., "claude-sonnet-4-5").
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    /**
     * Sets the Anthropic API client that sends the requests. Required.
     *
     * @param anthropicClient The client instance.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder anthropicClient(AnthropicClient anthropicClient) {
      this.anthropicClient = anthropicClient;
      return this;
    }

    /**
     * Sets the {@code max_tokens} sent when the request config does not set maxOutputTokens.
     * Defaults to 8192.
     *
     * @param maxTokens The maximum number of tokens to generate.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder maxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    /**
     * Sets how much reasoning effort Claude spends on each request. If unset, the model's default
     * applies.
     *
     * @param effort The reasoning effort, or null for the model's default.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder effort(OutputConfig.@Nullable Effort effort) {
      this.effort = effort;
      return this;
    }

    /**
     * Builds the {@link Claude} instance.
     *
     * @return A new {@link Claude} instance.
     * @throws NullPointerException if modelName or anthropicClient is not set.
     */
    public Claude build() {
      return new Claude(
          Objects.requireNonNull(modelName, "modelName must be set."),
          Objects.requireNonNull(anthropicClient, "anthropicClient must be set."),
          maxTokens,
          effort);
    }
  }

  /**
   * Returns a copy of {@code client} that shares its connections and sends ADK's tracking headers,
   * extending any the client already sets. A null client, or a test double that returns no copy, is
   * returned unchanged.
   */
  private static @Nullable AnthropicClient withTrackingHeaders(@Nullable AnthropicClient client) {
    if (client == null) {
      return null;
    }
    AnthropicClient tracked =
        client.withOptions(
            options -> {
              Headers configured = options.build().headers();
              Map<String, String> current = new HashMap<>();
              // Start from the SDK's default User-Agent, which it adds only when none is set.
              current.put(
                  "user-agent",
                  client.getClass().getSimpleName() + "/Java " + Properties.getPackageVersion());
              for (String name : TrackingHeaders.HEADERS.keySet()) {
                List<String> values = configured.values(name);
                if (!values.isEmpty()) {
                  current.put(name, String.join(" ", values));
                }
              }
              TrackingHeaders.mergeInto(current).forEach(options::replaceHeaders);
            });
    return tracked != null ? tracked : client;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    // Deferred so that a failure reaches the flow's model error callbacks.
    return Flowable.defer(
        () -> {
          PreparedRequest request = prepareRequest(llmRequest);
          return stream ? streamResponses(request) : Flowable.just(sendRequest(request));
        });
  }

  /** A request ready to send, with the tool that carries its response schema, if any. */
  private record PreparedRequest(
      MessageCreateParams params, @Nullable OutputSchemaTool outputSchemaTool) {}

  private PreparedRequest prepareRequest(LlmRequest llmRequest) {
    ToolUseIds toolUseIds = new ToolUseIds();
    List<MessageParam> messages = new ArrayList<>();
    for (Content content : llmRequest.contents()) {
      MessageParam message = contentToAnthropicMessageParam(content, toolUseIds);
      // Anthropic rejects empty messages, e.g. a turn whose parts were all dropped.
      if (!message.content().asBlockParams().isEmpty()) {
        messages.add(message);
      }
    }
    Optional<GenerateContentConfig> config = llmRequest.config();
    ImmutableList<FunctionDeclaration> declarations =
        config.flatMap(GenerateContentConfig::tools).orElse(ImmutableList.of()).stream()
            .flatMap(tool -> tool.functionDeclarations().orElse(ImmutableList.of()).stream())
            .collect(toImmutableList());
    @Nullable ThinkingConfigParam thinking =
        config
            .flatMap(GenerateContentConfig::thinkingConfig)
            .flatMap(this::thinkingParam)
            .orElse(null);

    boolean thinkingEnabled = thinking != null && !thinking.isDisabled();
    @Nullable CacheControlEphemeral cacheControl = cacheControl(llmRequest);

    MessageCreateParams.Builder params =
        MessageCreateParams.builder()
            .model(resolveModelName(llmRequest.model().orElse(model())))
            .messages(cacheControl == null ? messages : withCacheBreakpoint(messages, cacheControl))
            .maxTokens(config.flatMap(GenerateContentConfig::maxOutputTokens).orElse(maxTokens));
    String systemText =
        llmRequest.getSystemInstructions().stream()
            .filter(text -> !text.isEmpty())
            .collect(joining("\n"));
    if (!systemText.isEmpty()) {
      if (cacheControl == null) {
        params.system(systemText);
      } else {
        params.systemOfTextBlockParams(
            ImmutableList.of(
                TextBlockParam.builder().text(systemText).cacheControl(cacheControl).build()));
      }
    }

    @Nullable OutputSchemaTool outputSchemaTool = null;
    List<Tool> tools = new ArrayList<>();
    Optional<Schema> responseSchema = config.flatMap(GenerateContentConfig::responseSchema);
    if (responseSchema.isPresent() && declarations.isEmpty()) {
      // With tools present, the flow adds the set_model_response tool instead.
      outputSchemaTool = OutputSchemaTool.forSchema(responseSchema.get());
      tools.add(outputSchemaTool.tool());
      params.toolChoice(outputSchemaTool.choice(thinkingEnabled));
    } else if (!declarations.isEmpty()) {
      declarations.forEach(
          declaration -> tools.add(functionDeclarationToAnthropicTool(declaration)));
      params.toolChoice(ToolChoice.ofAuto(ToolChoiceAuto.builder().build()));
    }
    if (!tools.isEmpty()) {
      if (cacheControl != null) {
        int last = tools.size() - 1;
        tools.set(last, tools.get(last).toBuilder().cacheControl(cacheControl).build());
      }
      params.tools(tools.stream().map(ToolUnion::ofTool).collect(toImmutableList()));
    }

    if (thinking != null) {
      params.thinking(thinking);
    }
    if (effort != null) {
      params.outputConfig(OutputConfig.builder().effort(effort).build());
    }
    config.ifPresent(c -> setSamplingParameters(params, c, thinkingEnabled));
    config
        .flatMap(GenerateContentConfig::stopSequences)
        .filter(stopSequences -> !stopSequences.isEmpty())
        .ifPresent(params::stopSequences);
    return new PreparedRequest(params.build(), outputSchemaTool);
  }

  /**
   * Sends temperature, topP and topK unless thinking or effort is on, as ADK Python does; Claude
   * rejects them while it reasons. Models released after Claude Opus 4.6 reject non-default values
   * in every request.
   */
  @SuppressWarnings("deprecation") // Deprecated because models after Claude Opus 4.6 reject them.
  private void setSamplingParameters(
      MessageCreateParams.Builder params, GenerateContentConfig config, boolean thinkingEnabled) {
    if (config.temperature().isEmpty() && config.topP().isEmpty() && config.topK().isEmpty()) {
      return;
    }
    if (thinkingEnabled || effort != null) {
      if (warnedIgnoredSampling.compareAndSet(false, true)) {
        logger.warn("Claude ignores temperature, topP and topK while thinking or effort is on.");
      }
      return;
    }
    config.temperature().ifPresent(temperature -> params.temperature(decimal(temperature)));
    config.topP().ifPresent(topP -> params.topP(decimal(topP)));
    config.topK().ifPresent(topK -> params.topK(topK.longValue()));
  }

  /** Widens {@code value} through its decimal form, so 0.7f is sent as 0.7 and not 0.699999988. */
  private static double decimal(float value) {
    return Double.parseDouble(Float.toString(value));
  }

  /**
   * Returns the cache breakpoint for {@code llmRequest}, or null without a cache config. Also
   * returns null when the previous prompt was smaller than the config's minTokens, as in ADK
   * Python.
   */
  private static @Nullable CacheControlEphemeral cacheControl(LlmRequest llmRequest) {
    ContextCacheConfig cacheConfig = llmRequest.cacheConfig().orElse(null);
    if (cacheConfig == null
        || llmRequest
            .cacheableContentsTokenCount()
            .map(tokens -> tokens < cacheConfig.minTokens())
            .orElse(false)) {
      return null;
    }
    CacheControlEphemeral.Builder cacheControl = CacheControlEphemeral.builder();
    // An hour is the longest Claude keeps a prefix; a shorter lifetime gets the 5-minute default.
    if (cacheConfig.ttl().compareTo(Duration.ofHours(1)) >= 0) {
      cacheControl.ttl(CacheControlEphemeral.Ttl.TTL_1H);
    }
    return cacheControl.build();
  }

  /**
   * Marks the last block Claude can cache, so the next request reads the conversation so far from
   * the cache.
   */
  private static List<MessageParam> withCacheBreakpoint(
      List<MessageParam> messages, CacheControlEphemeral cacheControl) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      List<ContentBlockParam> blocks = new ArrayList<>(messages.get(i).content().asBlockParams());
      for (int j = blocks.size() - 1; j >= 0; j--) {
        Optional<ContentBlockParam> marked = withCacheControl(blocks.get(j), cacheControl);
        if (marked.isPresent()) {
          blocks.set(j, marked.get());
          List<MessageParam> markedMessages = new ArrayList<>(messages);
          markedMessages.set(i, messages.get(i).toBuilder().contentOfBlockParams(blocks).build());
          return markedMessages;
        }
      }
    }
    return messages;
  }

  /** Returns {@code block} with a cache breakpoint, or empty for a thinking block. */
  private static Optional<ContentBlockParam> withCacheControl(
      ContentBlockParam block, CacheControlEphemeral cacheControl) {
    if (block.isText()) {
      return Optional.of(
          ContentBlockParam.ofText(block.asText().toBuilder().cacheControl(cacheControl).build()));
    } else if (block.isImage()) {
      return Optional.of(
          ContentBlockParam.ofImage(
              block.asImage().toBuilder().cacheControl(cacheControl).build()));
    } else if (block.isDocument()) {
      return Optional.of(
          ContentBlockParam.ofDocument(
              block.asDocument().toBuilder().cacheControl(cacheControl).build()));
    } else if (block.isToolUse()) {
      return Optional.of(
          ContentBlockParam.ofToolUse(
              block.asToolUse().toBuilder().cacheControl(cacheControl).build()));
    } else if (block.isToolResult()) {
      return Optional.of(
          ContentBlockParam.ofToolResult(
              block.asToolResult().toBuilder().cacheControl(cacheControl).build()));
    }
    return Optional.empty();
  }

  /**
   * Maps a thinking budget to Claude's thinking mode as ADK Python does. A budget of 0 turns
   * thinking off, a negative one lets the model choose its depth, and a positive one sets a token
   * budget. Unlike ADK Python, which rejects a missing budget, this sends no thinking mode, so the
   * model's default applies.
   */
  private Optional<ThinkingConfigParam> thinkingParam(ThinkingConfig thinkingConfig) {
    if (thinkingConfig.thinkingLevel().isPresent()
        && effort == null
        && warnedThinkingLevel.compareAndSet(false, true)) {
      logger.warn(
          "Claude ignores ThinkingConfig.thinkingLevel; set effort with"
              + " Claude.builder().effort() instead.");
    }
    if (thinkingConfig.thinkingBudget().isEmpty()) {
      if (warnedMissingThinkingBudget.compareAndSet(false, true)) {
        logger.warn(
            "Claude sends no thinking mode for a ThinkingConfig without thinkingBudget, so the"
                + " model's default applies. Set thinkingBudget to 0 to turn thinking off, -1 to"
                + " let the model choose its depth, or 1024 or more for a token budget.");
      }
      return Optional.empty();
    }
    int budget = thinkingConfig.thinkingBudget().get();
    if (budget == 0) {
      return Optional.of(ThinkingConfigParam.ofDisabled(ThinkingConfigDisabled.builder().build()));
    }
    if (budget < 0) {
      // Without a display mode, newer models send back their reasoning redacted.
      return Optional.of(
          ThinkingConfigParam.ofAdaptive(
              ThinkingConfigAdaptive.builder()
                  .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                  .build()));
    }
    return Optional.of(ThinkingConfigParam.ofEnabled(budget));
  }

  /** Returns the bare model id when {@code model} is a Vertex AI resource name. */
  private static String resolveModelName(String model) {
    Matcher matcher = VERTEX_RESOURCE_NAME.matcher(model);
    return matcher.find() ? matcher.group(1) : model;
  }

  private LlmResponse sendRequest(PreparedRequest request) {
    Message message = anthropicClient.messages().create(request.params());
    logger.debug(
        "Claude response: {} content blocks, stop reason {}",
        message.content().size(),
        message.stopReason().map(StopReason::asString).orElse("none"));
    return convertAnthropicResponseToLlmResponse(message, request.outputSchemaTool());
  }

  private Flowable<LlmResponse> streamResponses(PreparedRequest request) {
    return Flowable.using(
        () -> anthropicClient.messages().createStreaming(request.params()),
        response -> {
          StreamState state = new StreamState(request.outputSchemaTool());
          return Flowable.fromStream(response.stream())
              .concatMapIterable(state::accept)
              .concatWith(Flowable.fromCallable(state::finish));
        },
        StreamResponse::close);
  }

  /** Turns streamed events into partial responses, then the whole message into a final one. */
  private static final class StreamState {
    private final MessageAccumulator accumulator = MessageAccumulator.create();
    private final @Nullable OutputSchemaTool outputSchemaTool;
    private final Set<Long> outputSchemaBlocks = new HashSet<>();
    private @Nullable String modelVersion;

    StreamState(@Nullable OutputSchemaTool outputSchemaTool) {
      this.outputSchemaTool = outputSchemaTool;
    }

    List<LlmResponse> accept(RawMessageStreamEvent event) {
      accumulator.accumulate(event);
      event.messageStart().ifPresent(start -> modelVersion = start.message().model().asString());
      event
          .contentBlockStart()
          .filter(start -> start.contentBlock().isToolUse())
          .ifPresent(
              start -> {
                // The accumulator rejects a tool_use block without input deltas; read it as {}.
                accumulator.accumulate(
                    RawMessageStreamEvent.ofContentBlockDelta(
                        RawContentBlockDeltaEvent.builder()
                            .index(start.index())
                            .delta(RawContentBlockDelta.ofInputJson(""))
                            .build()));
                if (outputSchemaTool != null
                    && start.contentBlock().asToolUse().name().equals(OUTPUT_SCHEMA_TOOL_NAME)) {
                  outputSchemaBlocks.add(start.index());
                }
              });
      Optional<RawContentBlockDeltaEvent> blockDelta = event.contentBlockDelta();
      if (blockDelta.isEmpty()) {
        return ImmutableList.of();
      }
      RawContentBlockDelta delta = blockDelta.get().delta();
      if (delta.text().isPresent()) {
        return ImmutableList.of(partial(Part.fromText(delta.text().get().text())));
      }
      if (delta.thinking().isPresent()) {
        return ImmutableList.of(
            partial(Part.builder().text(delta.thinking().get().thinking()).thought(true).build()));
      }
      // The input of the schema tool is the answer itself, unless it was nested to be an object.
      if (delta.inputJson().isPresent()
          && outputSchemaTool != null
          && !outputSchemaTool.nestsValue()
          && outputSchemaBlocks.contains(blockDelta.get().index())
          && !delta.inputJson().get().partialJson().isEmpty()) {
        return ImmutableList.of(partial(Part.fromText(delta.inputJson().get().partialJson())));
      }
      return ImmutableList.of();
    }

    LlmResponse finish() {
      return convertAnthropicResponseToLlmResponse(accumulator.message(), outputSchemaTool);
    }

    private LlmResponse partial(Part part) {
      return LlmResponse.builder()
          .content(Content.builder().role("model").parts(part).build())
          .modelVersion(modelVersion)
          .partial(true)
          .build();
    }
  }

  private static Role toClaudeRole(String role) {
    return role.equals("model") || role.equals("assistant") ? Role.ASSISTANT : Role.USER;
  }

  private static MessageParam contentToAnthropicMessageParam(
      Content content, ToolUseIds toolUseIds) {
    Role role = toClaudeRole(content.role().orElse(""));
    List<Part> parts = content.parts().orElse(ImmutableList.of());
    List<ContentBlockParam> blocks = new ArrayList<>();
    for (Part part : parts) {
      if (role.equals(Role.ASSISTANT) && isImageOrPdf(part)) {
        logger.warn("Dropping an image or PDF from an assistant turn, where Claude rejects them.");
        continue;
      }
      ContentBlockParam block = partToAnthropicMessageBlock(part, toolUseIds);
      if (block != null) {
        blocks.add(block);
      }
    }
    // Keep a user turn of only empty text as ".", or a last assistant turn would be prefill.
    if (blocks.isEmpty()
        && role.equals(Role.USER)
        && parts.stream().anyMatch(Claude::isEmptyTextPart)) {
      blocks.add(ContentBlockParam.ofText("."));
    }
    return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
  }

  /** Whether a part has any of the fields ADK Python counts as content. */
  private static boolean hasContentFields(Part part) {
    return part.functionCall().isPresent()
        || part.functionResponse().isPresent()
        || part.inlineData().isPresent()
        || part.fileData().isPresent()
        || part.executableCode().isPresent()
        || part.codeExecutionResult().isPresent()
        || part.toolCall().isPresent()
        || part.toolResponse().isPresent()
        || part.audioTranscription().isPresent();
  }

  /** Whether a non-thought part carries a signature and nothing to send, as in ADK Python. */
  private static boolean isContentFreeSignature(Part part) {
    return part.thoughtSignature().map(signature -> signature.length > 0).orElse(false)
        && !part.thought().orElse(false)
        && part.text().map(String::isEmpty).orElse(true)
        && !hasContentFields(part);
  }

  /** Whether a part carries only empty text, which Anthropic rejects; mirrors ADK Python. */
  private static boolean isEmptyTextPart(Part part) {
    return part.text().map(String::isEmpty).orElse(false)
        && part.thoughtSignature().map(signature -> signature.length == 0).orElse(true)
        && !hasContentFields(part);
  }

  private static boolean isImageOrPdf(Part part) {
    return part.inlineData()
        .map(Claude::mediaType)
        .filter(type -> type.startsWith("image/") || type.equals(PDF_MEDIA_TYPE))
        .isPresent();
  }

  private static @Nullable ContentBlockParam partToAnthropicMessageBlock(
      Part part, ToolUseIds toolUseIds) {
    if (isContentFreeSignature(part)) {
      logger.warn("Dropping a thought signature from another model.");
      return null;
    }
    if (isEmptyTextPart(part)) {
      return null;
    }
    if (part.thought().orElse(false)
        && part.functionCall().isEmpty()
        && part.functionResponse().isEmpty()) {
      // Signatures this class stores are UTF-8; a binary one (e.g. Gemini's) is not Claude's.
      Optional<String> signature =
          part.thoughtSignature()
              .filter(bytes -> bytes.length > 0 && Utf8.isWellFormed(bytes))
              .map(bytes -> new String(bytes, UTF_8));
      if (signature.isPresent()) {
        // Unlike ADK Python, empty text (display "omitted") stays thinking, not redacted_thinking.
        return part.text().isPresent()
            ? ContentBlockParam.ofThinking(
                ThinkingBlockParam.builder()
                    .thinking(part.text().get())
                    .signature(signature.get())
                    .build())
            : ContentBlockParam.ofRedactedThinking(
                RedactedThinkingBlockParam.builder().data(signature.get()).build());
      }
      // Other thoughts go out as text (ADK Python sends thinking), or are dropped without text.
      if (part.text().map(String::isEmpty).orElse(true)) {
        return null;
      }
    }
    // Unlike ADK Python, a call or response is always sent, even on a thought or beside text.
    if (part.functionCall().isPresent()) {
      FunctionCall call = part.functionCall().get();
      String name = call.name().orElseThrow();
      return ContentBlockParam.ofToolUse(
          ToolUseBlockParam.builder()
              .id(toolUseIds.forCall(call.id().orElse(null), name))
              .name(name)
              .input(JsonValue.from(call.args().orElse(ImmutableMap.of())))
              .build());
    } else if (part.functionResponse().isPresent()) {
      FunctionResponse response = part.functionResponse().get();
      return ContentBlockParam.ofToolResult(
          ToolResultBlockParam.builder()
              .toolUseId(
                  toolUseIds.forResult(response.id().orElse(null), response.name().orElse(null)))
              .content(toolResultText(response.response().orElse(ImmutableMap.of())))
              .isError(false)
              .build());
    } else if (part.text().filter(text -> !text.isEmpty()).isPresent()) {
      return ContentBlockParam.ofText(part.text().get());
    } else if (part.inlineData().isPresent()) {
      return inlineDataToBlock(part.inlineData().get());
    } else if (part.executableCode().isPresent()) {
      return ContentBlockParam.ofText(
          "Code:```python\n" + part.executableCode().get().code().orElse("") + "\n```");
    } else if (part.codeExecutionResult().isPresent()) {
      return ContentBlockParam.ofText(
          "Execution Result:```code_output\n"
              + part.codeExecutionResult().get().output().orElse("")
              + "\n```");
    }
    throw new UnsupportedOperationException("Claude does not support this part type yet.");
  }

  /** Converts inline image or PDF data to a base64 block; Claude takes no other inline media. */
  private static ContentBlockParam inlineDataToBlock(Blob blob) {
    String mediaType = mediaType(blob);
    byte[] data =
        blob.data()
            .orElseThrow(
                () -> new IllegalArgumentException("Claude needs image and PDF data inline."));
    String base64 = Base64.getEncoder().encodeToString(data);
    if (mediaType.equals(PDF_MEDIA_TYPE)) {
      return ContentBlockParam.ofDocument(DocumentBlockParam.Source.ofBase64(base64));
    }
    if (IMAGE_MEDIA_TYPES.contains(mediaType)) {
      return ContentBlockParam.ofImage(
          ImageBlockParam.Source.ofBase64(
              Base64ImageSource.builder()
                  .data(base64)
                  .mediaType(Base64ImageSource.MediaType.of(mediaType))
                  .build()));
    }
    throw new IllegalArgumentException(
        "Claude accepts only JPEG, PNG, GIF and WebP images and PDF documents as inline data.");
  }

  /** The MIME type of {@code blob} without parameters, in lower case. */
  private static String mediaType(Blob blob) {
    String mimeType = blob.mimeType().orElse("");
    int parameters = mimeType.indexOf(';');
    return Ascii.toLowerCase(
        (parameters < 0 ? mimeType : mimeType.substring(0, parameters)).trim());
  }

  /**
   * Renders a function response as tool_result text, as ADK Python does: a {@code content} field is
   * sent as is, the value of a response that holds only {@code result} is unwrapped, and any other
   * response is sent as JSON.
   */
  private static String toolResultText(Map<String, Object> response) {
    Object content = response.get("content");
    if (content instanceof List<?> items && !items.isEmpty()) {
      return items.stream().map(Claude::contentItemText).collect(joining("\n"));
    }
    if (content instanceof String text && !text.isEmpty()) {
      return text;
    }
    Object result = response.get("result");
    if (response.size() == 1 && result != null) {
      return toText(result);
    }
    return response.isEmpty() ? "" : serializeToJson(response);
  }

  private static String contentItemText(Object item) {
    if (item instanceof Map<?, ?> map
        && "text".equals(map.get("type"))
        && map.get("text") != null) {
      return map.get("text").toString();
    }
    return toText(item);
  }

  private static String toText(@Nullable Object value) {
    return value instanceof String text ? text : serializeToJson(value);
  }

  private static String serializeToJson(@Nullable Object obj) {
    try {
      return JsonBaseModel.getMapper().writeValueAsString(obj);
    } catch (JsonProcessingException e) {
      logger.warn(
          "Failed to serialize a {} to JSON ({}); sending its string form.",
          obj == null ? "null" : obj.getClass().getName(),
          e.getClass().getSimpleName());
      return String.valueOf(obj);
    }
  }

  /**
   * Keeps tool_use and tool_result ids paired when history ids are missing or invalid, such as a
   * call made by another model, as ADK Python does. One instance covers one request.
   */
  private static final class ToolUseIds {
    private record PendingCall(String name, String id) {}

    private final Map<String, String> remapped = new HashMap<>();
    // Calls without an id that no result has answered yet, oldest first.
    private final List<PendingCall> pending = new ArrayList<>();
    private int nextFallback;

    /** Returns the id to send for a call, queuing a fresh fallback when the call has none. */
    String forCall(@Nullable String id, String name) {
      String known = known(id);
      if (known != null) {
        return known;
      }
      String fallback = newFallback();
      pending.add(new PendingCall(name, fallback));
      return fallback;
    }

    /**
     * Returns the id to send for a result. One without an id takes the oldest pending call with the
     * same name, else the oldest pending call.
     */
    String forResult(@Nullable String id, @Nullable String name) {
      String known = known(id);
      if (known != null) {
        return known;
      }
      if (pending.isEmpty()) {
        return newFallback();
      }
      int index = 0;
      for (int i = 0; i < pending.size(); i++) {
        if (pending.get(i).name().equals(name)) {
          index = i;
          break;
        }
      }
      return pending.remove(index).id();
    }

    /** A valid id as is, an invalid one remapped consistently, or null when the id is missing. */
    private @Nullable String known(@Nullable String id) {
      if (id == null || id.isEmpty()) {
        return null;
      }
      if (VALID_TOOL_USE_ID.matcher(id).matches()) {
        return id;
      }
      return remapped.computeIfAbsent(id, unused -> newFallback());
    }

    private String newFallback() {
      return "toolu_fallback_" + nextFallback++;
    }
  }

  /**
   * Recursively lowercases JSON Schema {@code type} keywords for Anthropic compatibility.
   *
   * <p>{@code type} is only lowercased when it is a plain string. JSON Schema also permits a union
   * type array (e.g. {@code ["string", "null"]}), which MCP tools can emit; those are traversed
   * rather than cast to {@code String}. All nested sub-schemas (properties, {@code $defs}, {@code
   * anyOf}, {@code items}, ...) are visited so deeply-nested types are normalized too.
   */
  @SuppressWarnings("unchecked")
  private static void updateTypeString(Object value) {
    if (value instanceof List) {
      for (Object item : (List<Object>) value) {
        updateTypeString(item);
      }
      return;
    }
    if (!(value instanceof Map)) {
      return;
    }
    Map<String, Object> valueDict = (Map<String, Object>) value;

    Object schemaType = valueDict.get("type");
    if (schemaType instanceof String) {
      valueDict.put("type", ((String) schemaType).toLowerCase(Locale.ROOT));
    }

    for (String dictKey : NESTED_SCHEMA_MAP_KEYWORDS) {
      Object child = valueDict.get(dictKey);
      if (child instanceof Map) {
        for (Object childValue : ((Map<String, Object>) child).values()) {
          updateTypeString(childValue);
        }
      }
    }

    for (String singleKey : NESTED_SCHEMA_KEYWORDS) {
      updateTypeString(valueDict.get(singleKey));
    }

    for (String listKey : NESTED_SCHEMA_LIST_KEYWORDS) {
      updateTypeString(valueDict.get(listKey));
    }
  }

  private static Tool functionDeclarationToAnthropicTool(FunctionDeclaration functionDeclaration) {
    Map<String, Object> inputSchema;
    if (functionDeclaration.parametersJsonSchema().isPresent()) {
      // MCP tools populate parametersJsonSchema (a raw JSON Schema object) instead of the
      // structured parameters() field. Pass the whole schema through -- as a mutable copy -- so
      // keys such as $ref/$defs/additionalProperties are preserved rather than dropped, then
      // lowercase any type strings for Anthropic compatibility.
      inputSchema =
          JsonBaseModel.getMapper()
              .convertValue(
                  functionDeclaration.parametersJsonSchema().get(),
                  new TypeReference<Map<String, Object>>() {});
    } else {
      Map<String, Object> properties = new HashMap<>();
      List<String> required = new ArrayList<>();
      if (functionDeclaration.parameters().isPresent()
          && functionDeclaration.parameters().get().properties().isPresent()) {
        functionDeclaration
            .parameters()
            .get()
            .properties()
            .get()
            .forEach(
                (key, schema) ->
                    properties.put(
                        key,
                        JsonBaseModel.getMapper()
                            .convertValue(schema, new TypeReference<Map<String, Object>>() {})));
        functionDeclaration.parameters().get().required().ifPresent(required::addAll);
      }
      inputSchema = new HashMap<>();
      inputSchema.put("type", "object");
      inputSchema.put("properties", properties);
      if (!required.isEmpty()) {
        inputSchema.put("required", required);
      }
    }
    updateTypeString(inputSchema);

    return Tool.builder()
        .name(functionDeclaration.name().orElseThrow())
        .description(functionDeclaration.description().orElse(""))
        .inputSchema(toAnthropicInputSchema(inputSchema))
        .build();
  }

  /**
   * Builds an Anthropic {@link Tool.InputSchema} from a JSON Schema map, preserving every top-level
   * keyword (e.g. {@code $defs}, {@code additionalProperties}) as an additional property so schemas
   * that rely on {@code $ref}/{@code $defs} are not sent to Claude with dangling references.
   */
  private static Tool.InputSchema toAnthropicInputSchema(Map<String, Object> schema) {
    Tool.InputSchema.Builder builder = Tool.InputSchema.builder();
    schema.forEach(
        (key, value) -> {
          switch (key) {
            case "type":
              // The Anthropic input schema type is always "object"; skip to avoid a duplicate key.
              break;
            case "properties":
              builder.properties(JsonValue.from(value == null ? new HashMap<>() : value));
              break;
            case "required":
              if (value instanceof List) {
                List<String> required = new ArrayList<>();
                for (Object item : (List<?>) value) {
                  if (item != null) {
                    required.add(item.toString());
                  }
                }
                builder.required(required);
              } else {
                builder.putAdditionalProperty(key, JsonValue.from(value));
              }
              break;
            default:
              builder.putAdditionalProperty(key, JsonValue.from(value));
          }
        });
    return builder.build();
  }

  /**
   * A response schema sent as the one tool Claude is asked to call, as ADK Python does: Claude
   * takes a schema natively only on its newest models. The call is turned back into response text.
   */
  private record OutputSchemaTool(Tool tool, boolean nestsValue) {

    static OutputSchemaTool forSchema(Schema schema) {
      Map<String, Object> inputSchema =
          JsonBaseModel.getMapper()
              .convertValue(schema, new TypeReference<Map<String, Object>>() {});
      updateTypeString(inputSchema);
      // A tool input must be an object, so a list or scalar schema becomes its only property.
      boolean nestsValue = !"object".equals(inputSchema.get("type"));
      if (nestsValue) {
        inputSchema =
            ImmutableMap.of(
                "type",
                "object",
                "properties",
                ImmutableMap.of(OUTPUT_SCHEMA_VALUE_KEY, inputSchema),
                "required",
                ImmutableList.of(OUTPUT_SCHEMA_VALUE_KEY));
      }
      Tool tool =
          Tool.builder()
              .name(OUTPUT_SCHEMA_TOOL_NAME)
              .description(OUTPUT_SCHEMA_TOOL_DESCRIPTION)
              .inputSchema(toAnthropicInputSchema(inputSchema))
              .build();
      return new OutputSchemaTool(tool, nestsValue);
    }

    /** Forces one call to this tool, or offers it without forcing while Claude is thinking. */
    ToolChoice choice(boolean thinkingEnabled) {
      if (thinkingEnabled) {
        return ToolChoice.ofAuto(ToolChoiceAuto.builder().disableParallelToolUse(true).build());
      }
      return ToolChoice.ofTool(
          ToolChoiceTool.builder()
              .name(OUTPUT_SCHEMA_TOOL_NAME)
              .disableParallelToolUse(true)
              .build());
    }

    /** Replaces the call to this tool with the JSON answer it carries, keeping thoughts. */
    LlmResponse unwrap(LlmResponse response) {
      List<Part> parts = response.content().flatMap(Content::parts).orElse(ImmutableList.of());
      Optional<FunctionCall> call =
          parts.stream()
              .flatMap(part -> part.functionCall().stream())
              .filter(
                  functionCall -> functionCall.name().orElse("").equals(OUTPUT_SCHEMA_TOOL_NAME))
              .findFirst();
      if (call.isEmpty()) {
        return response;
      }
      Map<String, Object> args = call.get().args().orElse(ImmutableMap.of());
      ImmutableList.Builder<Part> answer = ImmutableList.builder();
      // Offered rather than forced, Claude may narrate first; that text is not the answer.
      parts.stream().filter(part -> part.thought().orElse(false)).forEach(answer::add);
      answer.add(
          Part.fromText(serializeToJson(nestsValue ? args.get(OUTPUT_SCHEMA_VALUE_KEY) : args)));
      return response.toBuilder()
          .content(response.content().get().toBuilder().parts(answer.build()).build())
          .build();
    }
  }

  private static LlmResponse convertAnthropicResponseToLlmResponse(
      Message message, @Nullable OutputSchemaTool outputSchemaTool) {
    GenerateContentResponseUsageMetadata usage = usageMetadata(message.usage());
    String modelVersion = message.model().asString();
    List<ContentBlock> blocks = message.content();
    Optional<StopReason.Value> stopReason = message.stopReason().map(StopReason::value);
    if (stopReason.filter(TRUNCATING_STOP_REASONS::contains).isPresent()
        && !blocks.isEmpty()
        && blocks.get(blocks.size() - 1).isToolUse()) {
      // The last call's input is incomplete, and running it would act on partial arguments.
      return LlmResponse.builder()
          .errorCode(new FinishReason(FinishReason.Known.MAX_TOKENS))
          .errorMessage(TRUNCATED_TOOL_USE_MESSAGE)
          .finishReason(new FinishReason(FinishReason.Known.MAX_TOKENS))
          .usageMetadata(usage)
          .modelVersion(modelVersion)
          .build();
    }
    LlmResponse response =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(
                        blocks.stream()
                            .map(Claude::anthropicContentBlockToPart)
                            .collect(toImmutableList()))
                    .build())
            .usageMetadata(usage)
            .finishReason(stopReason.map(Claude::toFinishReason).orElse(null))
            .modelVersion(modelVersion)
            .build();
    return outputSchemaTool == null ? response : outputSchemaTool.unwrap(response);
  }

  /** Maps Claude's stop reason to a finish reason as ADK Python does. */
  private static FinishReason toFinishReason(StopReason.Value stopReason) {
    return new FinishReason(
        switch (stopReason) {
          case END_TURN, STOP_SEQUENCE, TOOL_USE, PAUSE_TURN -> FinishReason.Known.STOP;
          case MAX_TOKENS, MODEL_CONTEXT_WINDOW_EXCEEDED -> FinishReason.Known.MAX_TOKENS;
          case REFUSAL -> FinishReason.Known.SAFETY;
          default -> FinishReason.Known.FINISH_REASON_UNSPECIFIED;
        });
  }

  /**
   * Folds the cache tokens Claude reports separately into the prompt count, and splits the thinking
   * tokens it counts as output into their own count, as the genai usage shape expects.
   */
  private static GenerateContentResponseUsageMetadata usageMetadata(Usage usage) {
    long promptTokens =
        usage.inputTokens()
            + usage.cacheReadInputTokens().orElse(0L)
            + usage.cacheCreationInputTokens().orElse(0L);
    long outputTokens = usage.outputTokens();
    Optional<Long> thinkingTokens =
        usage
            .outputTokensDetails()
            .flatMap(details -> details._thinkingTokens().asKnown())
            .map(tokens -> Math.min(tokens, outputTokens));
    GenerateContentResponseUsageMetadata.Builder builder =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(Math.toIntExact(promptTokens))
            .candidatesTokenCount(Math.toIntExact(outputTokens - thinkingTokens.orElse(0L)))
            .totalTokenCount(Math.toIntExact(promptTokens + outputTokens));
    usage
        .cacheReadInputTokens()
        .ifPresent(tokens -> builder.cachedContentTokenCount(Math.toIntExact(tokens)));
    thinkingTokens.ifPresent(tokens -> builder.thoughtsTokenCount(Math.toIntExact(tokens)));
    return builder.build();
  }

  private static Part anthropicContentBlockToPart(ContentBlock block) {
    if (block.isText()) {
      return Part.builder().text(block.asText().text()).build();
    } else if (block.isToolUse()) {
      ToolUseBlock toolUse = block.asToolUse();
      Map<String, Object> args =
          toolUse._input().convert(new TypeReference<Map<String, Object>>() {});
      return Part.builder()
          .functionCall(
              FunctionCall.builder()
                  .id(toolUse.id())
                  .name(toolUse.name())
                  // An "input": null block has no arguments, as in ADK Kotlin.
                  .args(args != null ? args : ImmutableMap.of())
                  .build())
          .build();
    } else if (block.isThinking()) {
      ThinkingBlock thinking = block.asThinking();
      Part.Builder part = Part.builder().text(thinking.thinking()).thought(true);
      if (!thinking.signature().isEmpty()) {
        part.thoughtSignature(thinking.signature().getBytes(UTF_8));
      }
      return part.build();
    } else if (block.isRedactedThinking()) {
      // Keep the encrypted data in thoughtSignature so the block can be sent back unchanged.
      return Part.builder()
          .thought(true)
          .thoughtSignature(block.asRedactedThinking().data().getBytes(UTF_8))
          .build();
    }
    throw new UnsupportedOperationException("Not supported yet.");
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException("Live connection is not supported for Claude models.");
  }

  /**
   * Reports {@link LlmCapabilities#outputSchemaAndTools()} as false: this class drops a response
   * schema sent beside function declarations.
   */
  @Override
  public LlmCapabilities capabilities() {
    return LlmCapabilities.builder().outputSchemaAndTools(false).build();
  }
}
