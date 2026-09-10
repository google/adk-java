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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import io.reactivex.rxjava3.core.Flowable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ModelProviderRegistry}.
 *
 * <p>Rather than fighting ServiceLoader's class-name-based instantiation with mocks (which use
 * synthetic class names), these tests use simple real implementations of {@link ModelProvider},
 * served to {@link java.util.ServiceLoader} through an in-memory {@code META-INF/services}
 * resource, and verify the effect on the real {@link LlmRegistry}.
 */
@RunWith(JUnit4.class)
public final class ModelProviderRegistryTest {

  // -----------------------------------------------------------------------
  // Lightweight real providers for ClassLoader-based ServiceLoader testing
  // -----------------------------------------------------------------------

  /** A minimal {@link BaseLlm} carrying only a model name. */
  static final class FakeLlm extends BaseLlm {
    FakeLlm(String modelName) {
      super(modelName);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
      return Flowable.empty();
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
      throw new UnsupportedOperationException();
    }
  }

  /** A real (non-mock) provider used to drive the ServiceLoader in tests. */
  public static final class AlphaTestProvider implements ModelProvider {
    @Override
    public String prefix() {
      return "spi-test-alpha";
    }

    @Override
    public BaseLlm createFromBareModelName(String bareModelName) {
      return new FakeLlm(bareModelName);
    }
  }

  /** A second real provider. */
  public static final class BetaTestProvider implements ModelProvider {
    @Override
    public String prefix() {
      return "spi-test-beta";
    }

    @Override
    public BaseLlm createFromBareModelName(String bareModelName) {
      return new FakeLlm(bareModelName);
    }
  }

  /**
   * A provider whose no-arg constructor throws, simulating a broken provider on the classpath.
   * {@link java.util.ServiceLoader} wraps the constructor failure in a {@link
   * java.util.ServiceConfigurationError} when the provider is instantiated.
   */
  public static final class FailingTestProvider implements ModelProvider {
    public FailingTestProvider() {
      throw new IllegalStateException("boom - simulated broken provider constructor");
    }

    @Override
    public String prefix() {
      return "spi-test-failing";
    }

    @Override
    public BaseLlm createFromBareModelName(String bareModelName) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Provider registered for the no-arg {@link ModelProviderRegistry#registerAll()} test through the
   * real {@code META-INF/services} file in {@code src/test/resources}.
   */
  public static final class ServiceFileTestProvider implements ModelProvider {
    @Override
    public String prefix() {
      return "spi-test-servicefile";
    }

    @Override
    public BaseLlm createFromBareModelName(String bareModelName) {
      return new FakeLlm(bareModelName);
    }
  }

  // -----------------------------------------------------------------------
  // registerAll(ClassLoader) tests
  // -----------------------------------------------------------------------

  @Test
  public void registerAll_singleProvider_registersPatternWithLlmRegistry() {
    ClassLoader cl =
        InMemoryServiceClassLoader.of(ModelProvider.class, List.of(AlphaTestProvider.class));

    List<ModelProvider> registered = ModelProviderRegistry.registerAll(cl);

    assertThat(registered).hasSize(1);
    assertThat(registered.get(0).modelPattern()).isEqualTo("spi-test-alpha/.*");
    assertThat(LlmRegistry.matchesAnyPattern("spi-test-alpha/some-model")).isTrue();
  }

  @Test
  public void registerAll_multipleProviders_registersAll() {
    ClassLoader cl =
        InMemoryServiceClassLoader.of(
            ModelProvider.class, List.of(AlphaTestProvider.class, BetaTestProvider.class));

    List<ModelProvider> registered = ModelProviderRegistry.registerAll(cl);

    assertThat(registered.stream().map(ModelProvider::modelPattern).toList())
        .containsExactly("spi-test-alpha/.*", "spi-test-beta/.*");
    assertThat(LlmRegistry.matchesAnyPattern("spi-test-beta/some-model")).isTrue();
  }

  @Test
  public void registerAll_noProviders_returnsEmptyListAndNothingRegistered() {
    ClassLoader cl = InMemoryServiceClassLoader.of(ModelProvider.class, List.of());

    List<ModelProvider> registered = ModelProviderRegistry.registerAll(cl);

    assertThat(registered).isEmpty();
    assertThat(LlmRegistry.matchesAnyPattern("spi-test-unregistered/some-model")).isFalse();
  }

  @Test
  public void registerAll_oneProviderFailsToInstantiate_othersStillRegister() {
    ClassLoader cl =
        InMemoryServiceClassLoader.of(
            ModelProvider.class,
            List.of(AlphaTestProvider.class, FailingTestProvider.class, BetaTestProvider.class));

    List<ModelProvider> registered = ModelProviderRegistry.registerAll(cl);

    // The broken provider is skipped; the two healthy providers still register.
    assertThat(registered.stream().map(ModelProvider::modelPattern).toList())
        .containsExactly("spi-test-alpha/.*", "spi-test-beta/.*");
    assertThat(LlmRegistry.matchesAnyPattern("spi-test-failing/some-model")).isFalse();
  }

  @Test
  public void registerAll_resolvedLlmReceivesBareModelName() {
    ClassLoader cl =
        InMemoryServiceClassLoader.of(ModelProvider.class, List.of(AlphaTestProvider.class));
    ModelProviderRegistry.registerAll(cl);

    BaseLlm llm = LlmRegistry.getLlm("spi-test-alpha/some-model-id");

    // The routing prefix is stripped: the wire-format model name is the bare identifier.
    assertThat(llm).isInstanceOf(FakeLlm.class);
    assertThat(llm.model()).isEqualTo("some-model-id");
  }

  @Test
  public void registerAll_noArg_discoversProviderFromTestClasspathServiceFile() {
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();

    assertThat(registered.stream().anyMatch(p -> p instanceof ServiceFileTestProvider)).isTrue();
    assertThat(LlmRegistry.matchesAnyPattern("spi-test-servicefile/some-model")).isTrue();
  }

  // -----------------------------------------------------------------------
  // Helper: ClassLoader backed by an in-memory META-INF/services file
  // -----------------------------------------------------------------------

  /**
   * A {@link ClassLoader} that serves a synthetic {@code META-INF/services/} resource listing the
   * given implementation classes, enabling {@link java.util.ServiceLoader} to discover them without
   * any real JAR on the classpath.
   */
  static final class InMemoryServiceClassLoader extends ClassLoader {

    private final String resourcePath;
    private final URL serviceFileUrl;

    // The URL(..., URLStreamHandler) constructor is deprecated (not for removal) as of Java 20,
    // but its replacement URL.of() does not exist in Java 17, this project's language level.
    @SuppressWarnings("deprecation")
    static InMemoryServiceClassLoader of(
        Class<?> serviceInterface, List<Class<?>> implementations) {
      StringBuilder sb = new StringBuilder();
      for (Class<?> impl : implementations) {
        sb.append(impl.getName()).append("\n");
      }

      byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

      try {
        URL url = new URL("mem", null, 0, "/", new InMemoryStreamHandler(bytes));
        return new InMemoryServiceClassLoader(
            "META-INF/services/" + serviceInterface.getName(), url);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to create in-memory URL", e);
      }
    }

    private InMemoryServiceClassLoader(String resourcePath, URL serviceFileUrl) {
      super(InMemoryServiceClassLoader.class.getClassLoader());
      this.resourcePath = resourcePath;
      this.serviceFileUrl = serviceFileUrl;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      if (resourcePath.equals(name)) {
        return Collections.enumeration(List.of(serviceFileUrl));
      }
      return super.getResources(name);
    }

    private static final class InMemoryStreamHandler extends URLStreamHandler {
      private final byte[] content;

      InMemoryStreamHandler(byte[] content) {
        this.content = content;
      }

      @Override
      protected URLConnection openConnection(URL u) {
        return new URLConnection(u) {
          @Override
          public void connect() {}

          @Override
          public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
          }
        };
      }
    }
  }
}
