package com.google.adk.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.*;
import com.google.genai.types.Content;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.reactivex.rxjava3.core.Flowable;

import java.util.*;

import static com.google.genai.types.Type.Known.OBJECT;

public class LangChain4j extends BaseLlm {

    private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE = new TypeReference<>() {
    };

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;

    public LangChain4j(ChatModel chatModel) { // TODO
        super(chatModel.defaultRequestParameters().modelName());
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel = null;
        this.objectMapper = new ObjectMapper();
    }

    public LangChain4j(ChatModel chatModel, String modelName) { // TODO
        super(modelName);
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel = null;
        this.objectMapper = new ObjectMapper();
    }

    public LangChain4j(StreamingChatModel streamingChatModel) { // TODO
        super(streamingChatModel.defaultRequestParameters().modelName());
        this.chatModel = null;
        this.streamingChatModel = Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
    }

    public LangChain4j(StreamingChatModel streamingChatModel, String modelName) { // TODO
        super(modelName);
        this.chatModel = null;
        this.streamingChatModel = Objects.requireNonNull(streamingChatModel, "streamingChatModel cannot be null");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            // TODO
            throw new UnsupportedOperationException("Streaming is not supported for LangChain4j models yet.");
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            ChatRequest chatRequest = toChatRequest(llmRequest);
            ChatResponse chatResponse = chatModel.chat(chatRequest);
            LlmResponse llmResponse = toLlmResponse(chatResponse);
            return Flowable.just(llmResponse);
        }
    }

    private ChatRequest toChatRequest(LlmRequest llmRequest) {
        // TODO llmRequest.model() ?
        return ChatRequest.builder()
            .messages(toMessages(llmRequest))
            .toolSpecifications(toToolSpecifications(llmRequest))
            // TODO
            .build();
    }

    private List<ChatMessage> toMessages(LlmRequest llmRequest) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.addAll(llmRequest.getSystemInstructions().stream().map(SystemMessage::from).toList());
        messages.addAll(llmRequest.contents().stream().map(this::toChatMessage).toList());
        return messages;
    }

    private ChatMessage toChatMessage(Content content) {
        String role = content.role().orElseThrow().toLowerCase(); // TODO
        return switch (role) {
            case "user" -> toUserOrToolResultMessage(content);
            case "model", "assistant" -> toAiMessage(content);
            default -> throw new IllegalStateException("Unexpected role: " + role);
        };
    }

    private ChatMessage toUserOrToolResultMessage(Content content) {

        List<String> texts = new ArrayList<>();
        ToolExecutionResultMessage toolExecutionResultMessage = null;

        for (Part part : content.parts().orElse(List.of())) {
            if (part.text().isPresent()) {
                texts.add(part.text().get());
            } else if (part.functionResponse().isPresent()) {
                // TODO multiple tool calls?
                FunctionResponse functionResponse = part.functionResponse().get();
                toolExecutionResultMessage = ToolExecutionResultMessage.from(
                    functionResponse.id().orElseThrow(),
                    functionResponse.name().orElseThrow(),
                    toJson(functionResponse.response().orElseThrow())
                );
            } else {
                throw new IllegalStateException("Either text or functionCall is expected, but was: " + part);
            }
        }

        if (toolExecutionResultMessage != null) {
            return toolExecutionResultMessage;
        } else {
            return UserMessage.from(String.join("\n", texts));
        }
    }

    private AiMessage toAiMessage(Content content) {

        List<String> texts = new ArrayList<>();
        List<ToolExecutionRequest> toolExecutionRequests = new ArrayList<>();

        content.parts().orElse(List.of()).forEach(part -> {
            if (part.text().isPresent()) {
                texts.add(part.text().get());
            } else if (part.functionCall().isPresent()) {
                FunctionCall functionCall = part.functionCall().get();
                ToolExecutionRequest toolExecutionRequest = ToolExecutionRequest.builder()
                    .id(functionCall.id().orElseThrow())
                    .name(functionCall.name().orElseThrow())
                    .arguments(toJson(functionCall.args().orElseThrow()))
                    .build();
                toolExecutionRequests.add(toolExecutionRequest);
            } else {
                throw new IllegalStateException("Either text or functionCall is expected, but was: " + part);
            }
        });

        return AiMessage.builder()
            .text(String.join("\n", texts))
            .toolExecutionRequests(toolExecutionRequests)
            .build();
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private List<ToolSpecification> toToolSpecifications(LlmRequest llmRequest) {

        List<ToolSpecification> toolSpecifications = new ArrayList<>();

        llmRequest.tools().values()
            .forEach(baseTool -> {
                if (baseTool instanceof FunctionTool functionTool) { // TODO MCP tool, LongRunningFunctionTool, etc
                    if (functionTool.declaration().isPresent()) {
                        FunctionDeclaration functionDeclaration = functionTool.declaration().get();
                        if (functionDeclaration.parameters().isPresent()) {
                            Schema schema = functionDeclaration.parameters().get();
                            ToolSpecification toolSpecification = ToolSpecification.builder()
                                .name(functionTool.name())
                                .description(functionTool.description())
                                .parameters(toParameters(schema)) // TODO
                                .build();
                            toolSpecifications.add(toolSpecification);
                        }
                    }
                } else {
                    throw new UnsupportedOperationException("LangChain4jLlm does not support tool of type: " + baseTool.getClass());
                }
            });

        return toolSpecifications; // TODO
    }

    private JsonObjectSchema toParameters(Schema schema) {
        if (schema.type().isPresent() && schema.type().get().knownEnum().equals(OBJECT)) {

            return JsonObjectSchema.builder()
                .addProperties(toProperties(schema))
                .required(schema.required().orElse(List.of()))
                .build(); // TODO
        } else {
            throw new UnsupportedOperationException("LangChain4jLlm does not support schema of type: " + schema.type());
        }
    }

    private Map<String, JsonSchemaElement> toProperties(Schema schema) {
        Map<String, Schema> properties = schema.properties().orElse(Map.of());
        Map<String, JsonSchemaElement> result = new HashMap<>();
        properties.forEach((k, v) -> result.put(k, toJsonSchemaElement(v)));
        return result;
    }

    private JsonSchemaElement toJsonSchemaElement(Schema schema) {
        Type type = schema.type().get(); // TODO
        return switch (type.knownEnum()) {
            case STRING -> JsonStringSchema.builder()
                .description(schema.description().orElse(null))
                .build();
            case NUMBER -> JsonNumberSchema.builder()
                .description(schema.description().orElse(null))
                .build();
            case INTEGER -> JsonIntegerSchema.builder()
                .description(schema.description().orElse(null))
                .build();
            case BOOLEAN -> JsonBooleanSchema.builder()
                .description(schema.description().orElse(null))
                .build();
            case ARRAY -> JsonArraySchema.builder()
                .description(schema.description().orElse(null))
                .items(toJsonSchemaElement(schema.items().orElseThrow()))
                .build();
            case OBJECT -> toParameters(schema);
            case TYPE_UNSPECIFIED ->
                throw new UnsupportedFeatureException("LangChain4jLlm does not support schema of type: " + type);
        };
    }

    private LlmResponse toLlmResponse(ChatResponse chatResponse) {

        Content content = Content.builder()
            .role("model")
            .parts(toParts(chatResponse.aiMessage()))
            .build();

        return LlmResponse.builder()
            .content(content)
            .build();
    }

    private List<Part> toParts(AiMessage aiMessage) {
        if (aiMessage.hasToolExecutionRequests()) {
            List<Part> parts = new ArrayList<>();
            aiMessage.toolExecutionRequests().forEach(toolExecutionRequest -> {
                FunctionCall functionCall = FunctionCall.builder()
                    .id(toolExecutionRequest.id() != null ? toolExecutionRequest.id() : UUID.randomUUID().toString())
                    .name(toolExecutionRequest.name())
                    .args(toArgs(toolExecutionRequest))
                    .build();
                Part part = Part.builder()
                    .functionCall(functionCall)
                    .build();
                parts.add(part);
            });
            return parts;
        } else {
            Part part = Part.builder()
                .text(aiMessage.text())
                .build();
            return List.of(part);
        }
    }

    private Map<String, Object> toArgs(ToolExecutionRequest toolExecutionRequest) {
        try {
            return objectMapper.readValue(toolExecutionRequest.arguments(), MAP_TYPE_REFERENCE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Live connection is not supported for LangChain4j models.");
    }
}