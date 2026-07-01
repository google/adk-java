package com.google.adk.models.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link ModelProviderRegistry}.
 *
 * <p>Rather than fighting ServiceLoader's class-name-based instantiation with Mockito mocks (which
 * use synthetic class names), we use a simple real implementation of {@link ModelProvider} and
 * verify the interaction with {@link LlmRegistry} via Mockito static mocking.
 */
@ExtendWith(MockitoExtension.class)
class ModelProviderRegistryTest {

  // -----------------------------------------------------------------------
  // Lightweight real providers for ClassLoader-based ServiceLoader testing
  // -----------------------------------------------------------------------

  /** A real (non-mock) provider used to drive the ServiceLoader in tests. */
  public static final class GroqTestProvider implements ModelProvider {
    @Override
    public String modelPattern() {
      return "groq/.*";
    }

    @Override
    public BaseLlm create(String modelName) {
      throw new UnsupportedOperationException("not needed in unit tests");
    }
  }

  /** A second real provider. */
  public static final class OllamaTestProvider implements ModelProvider {
    @Override
    public String modelPattern() {
      return "ollama/.*";
    }

    @Override
    public BaseLlm create(String modelName) {
      throw new UnsupportedOperationException("not needed in unit tests");
    }
  }

  // -----------------------------------------------------------------------
  // registerAll(ClassLoader) tests
  // -----------------------------------------------------------------------

  @Test
  void registerAll_singleProvider_registersPatternWithLlmRegistry() {
    ClassLoader cl =
        InMemoryServiceClassLoader.of(ModelProvider.class, List.of(GroqTestProvider.class));

    try (MockedStatic<LlmRegistry> registry = mockStatic(LlmRegistry.class)) {
      List<ModelProvider> registered = ModelProviderRegistry.registerAll(cl);

      assertThat(registered).hasSize(1);
      assertThat(registered.get(0).modelPattern()).isEqualTo("groq/.*");
      registry.verify(() -> LlmRegistry.registerLlm(anyString(), any()), times(1));
    }
  }

  @Test
  void registerAll_multipleProviders_registersAll() {
    ClassLoader cl =
        InMemoryServiceClassLoader.of(
            ModelProvider.class, List.of(GroqTestProvider.class, OllamaTestProvider.class));

    try (MockedStatic<LlmRegistry> registry = mockStatic(LlmRegistry.class)) {
      List<ModelProvider> registered = ModelProviderRegistry.registerAll(cl);

      assertThat(registered).hasSize(2);
      assertThat(registered.stream().map(ModelProvider::modelPattern))
          .containsExactlyInAnyOrder("groq/.*", "ollama/.*");
      registry.verify(() -> LlmRegistry.registerLlm(anyString(), any()), times(2));
    }
  }

  @Test
  void registerAll_noProviders_returnsEmptyListAndNothingRegistered() {
    ClassLoader cl = InMemoryServiceClassLoader.of(ModelProvider.class, List.of());

    try (MockedStatic<LlmRegistry> registry = mockStatic(LlmRegistry.class)) {
      List<ModelProvider> registered = ModelProviderRegistry.registerAll(cl);

      assertThat(registered).isEmpty();
      registry.verify(() -> LlmRegistry.registerLlm(anyString(), any()), never());
    }
  }

  @Test
  void registerAll_calledTwice_registersEachTimeIndependently() {
    ClassLoader cl =
        InMemoryServiceClassLoader.of(ModelProvider.class, List.of(GroqTestProvider.class));

    try (MockedStatic<LlmRegistry> registry = mockStatic(LlmRegistry.class)) {
      ModelProviderRegistry.registerAll(cl);
      ModelProviderRegistry.registerAll(cl);

      // LlmRegistry.registerLlm called once per invocation × 2 invocations = 2
      registry.verify(() -> LlmRegistry.registerLlm(anyString(), any()), times(2));
    }
  }

  // -----------------------------------------------------------------------
  // Helper: ClassLoader backed by an in-memory META-INF/services file
  // -----------------------------------------------------------------------

  /**
   * Constructs a {@link ClassLoader} that serves a synthetic {@code META-INF/services/} resource
   * listing the given implementation classes, enabling {@link java.util.ServiceLoader} to discover
   * them without any real JAR on the classpath.
   */
  static final class InMemoryServiceClassLoader extends ClassLoader {

    private final String resourcePath;
    private final byte[] serviceFileContent;

    static InMemoryServiceClassLoader of(
        Class<?> serviceInterface, List<Class<?>> implementations) {
      StringBuilder sb = new StringBuilder();
      for (Class<?> impl : implementations) {
        sb.append(impl.getName()).append("\n");
      }
      return new InMemoryServiceClassLoader(
          "META-INF/services/" + serviceInterface.getName(),
          sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private InMemoryServiceClassLoader(String resourcePath, byte[] content) {
      super(InMemoryServiceClassLoader.class.getClassLoader());
      this.resourcePath = resourcePath;
      this.serviceFileContent = content;
    }

    @Override
    public java.util.Enumeration<java.net.URL> getResources(String name)
        throws java.io.IOException {
      if (resourcePath.equals(name)) {
        byte[] bytes = serviceFileContent;
        java.net.URL url =
            new java.net.URL(
                "mem",
                null,
                0,
                "/",
                new java.net.URLStreamHandler() {
                  @Override
                  protected java.net.URLConnection openConnection(java.net.URL u) {
                    return new java.net.URLConnection(u) {
                      @Override
                      public void connect() {}

                      @Override
                      public java.io.InputStream getInputStream() {
                        return new java.io.ByteArrayInputStream(bytes);
                      }
                    };
                  }
                });
        return java.util.Collections.enumeration(List.of(url));
      }
      return super.getResources(name);
    }
  }
}
