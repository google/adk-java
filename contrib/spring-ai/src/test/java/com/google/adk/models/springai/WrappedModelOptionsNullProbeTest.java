package com.google.adk.models.springai;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Probe: a delegating/wrapping ChatModel that does not override getOptions() (interface default
 * returns null) makes the bridge build provider-neutral ToolCallingChatOptions, which
 * OpenAiChatModel.createRequest(...) cannot cast — ClassCastException on the first call.
 */
class WrappedModelOptionsNullProbeTest {

  /** Minimal delegating wrapper that forgets to override getOptions() — the common shape. */
  static final class ForgettingWrapper implements ChatModel, StreamingChatModel {
    private final ChatModel delegate;

    ForgettingWrapper(ChatModel delegate) {
      this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return ((StreamingChatModel) delegate).stream(prompt);
    }
    // getOptions() intentionally NOT overridden -> default null
  }

  @Test
  void wrappedModelWithoutOptions_nonStreaming_promptCarriesProviderNeutralOptions() {
    ChatModel inner = mock(ChatModel.class);
    // 模拟 OpenAiChatModel.createRequest 的强转行为：非 OpenAiChatOptions 即抛 CCE
    when(inner.call(any(Prompt.class)))
        .thenAnswer(
            inv -> {
              Prompt p = inv.getArgument(0);
              // 这是 OpenAiChatModel.createRequest 里的真实行为（强转）：
              if (p.getOptions() != null) {
                p.getOptions().getClass().cast(p.getOptions()); // no-op for same type
              }
              // 模拟强转：provider 只接受自己的 options 类型
              Object opts = p.getOptions();
              if (opts != null && !(opts.getClass().getName().contains("OpenAi"))) {
                throw new ClassCastException(
                    "Class cannot be cast to class org.springframework.ai.openai.OpenAiChatOptions ("
                        + opts.getClass().getName()
                        + ")");
              }
              return new ChatResponse(
                  List.of(
                      new Generation(
                          new AssistantMessage("ok"),
                          ChatGenerationMetadata.builder().finishReason("STOP").build())));
            });

    ForgettingWrapper wrapper = new ForgettingWrapper(inner);
    SpringAI springAI = new SpringAI(wrapper);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder().role("user").parts(List.of(Part.fromText("hello"))).build()))
            .tools(Map.of("ping", simpleTool()))
            .build();

    System.out.println(
        "[CHECK] request.tools = " + (request.tools() == null ? "null" : request.tools().size()));
    Throwable error = null;
    try {
      springAI.generateContent(request, false).blockingSubscribe();
    } catch (Throwable t) {
      error = t;
    }
    // 修复后期望：fail-fast 的 IllegalStateException（含指引），而非裸 ClassCastException
    System.out.println("[CHECK] wrapper.getOptions() = " + wrapper.getOptions());
    System.out.println(
        "[CHECK] instanceof ToolCallingChatOptions? = "
            + (wrapper.getOptions()
                instanceof org.springframework.ai.model.tool.ToolCallingChatOptions));
    System.out.println(
        "[PROBE] non-streaming error: " + (error == null ? "none" : error.toString()));
    // With warn-only: same ClassCastException (behavior unchanged), plus an actionable WARN.
    String root = error.toString();
    Throwable cur = error;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
      root += " | cause: " + cur.toString();
    }
    System.out.println("[PROBE] root chain: " + root);
    assertTrue(root.contains("ClassCastException"), "expected the cast failure, got: " + root);
  }

  private static com.google.adk.tools.BaseTool simpleTool() {
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("ping")
            .description("ping tool")
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(Map.of("q", Schema.builder().type("STRING").build()))
                    .build())
            .build();
    return new com.google.adk.tools.BaseTool("ping", "ping tool") {
      @Override
      public Optional<FunctionDeclaration> declaration() {
        return Optional.of(declaration);
      }

      @Override
      public Single<Map<String, Object>> runAsync(
          Map<String, Object> args, com.google.adk.tools.ToolContext toolContext) {
        return Single.just(Map.of());
      }
    };
  }
}
