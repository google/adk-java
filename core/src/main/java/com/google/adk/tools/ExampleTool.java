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

package com.google.adk.tools;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.examples.BaseExampleProvider;
import com.google.adk.examples.Example;
import com.google.adk.examples.ExampleUtils;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A tool that injects (few-shot) examples into the outgoing LLM request as system instructions.
 *
 * <p>Configuration (args) options for YAML:
 *
 * <ul>
 *   <li><b>examples</b>: Either a fully-qualified reference to a {@link BaseExampleProvider}
 *       instance (e.g., <code>com.example.MyExamples.INSTANCE</code>) or a list of examples with
 *       fields <code>input</code> and <code>output</code> (array of messages).
 * </ul>
 */
public final class ExampleTool extends BaseTool {

  private final Optional<BaseExampleProvider> exampleProvider;
  private final List<Example> examples;

  /** Single private constructor; create via builder or fromConfig. */
  private ExampleTool(Builder builder) {
    super(
        isNullOrEmpty(builder.name) ? "example_tool" : builder.name,
        isNullOrEmpty(builder.description)
            ? "Adds few-shot examples to the request"
            : builder.description);
    this.exampleProvider = builder.provider;
    this.examples = builder.examples;
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    // Do not add anything if no user text
    String query =
        toolContext
            .userContent()
            .flatMap(content -> content.parts().flatMap(parts -> parts.stream().findFirst()))
            .flatMap(part -> part.text())
            .orElse("");
    if (query.isEmpty()) {
      return Completable.complete();
    }

    final String examplesBlock;
    if (exampleProvider.isPresent()) {
      examplesBlock = ExampleUtils.buildExampleSi(exampleProvider.get(), query);
    } else if (!examples.isEmpty()) {
      // Adapter provider that returns a fixed list irrespective of query
      BaseExampleProvider provider = (unusedQuery) -> examples;
      examplesBlock = ExampleUtils.buildExampleSi(provider, query);
    } else {
      return Completable.complete();
    }

    llmRequestBuilder.appendInstructions(ImmutableList.of(examplesBlock));
    // Delegate to BaseTool to keep any declaration bookkeeping (none for this tool)
    return super.processLlmRequest(llmRequestBuilder, toolContext);
  }

  /** Factory from YAML tool args. */
  public static ExampleTool fromConfig(ToolArgsConfig args, String configAbsPath)
      throws ConfigurationException {
    if (args == null || args.isEmpty()) {
      throw new ConfigurationException("ExampleTool requires 'examples' argument");
    }

    var maybeExamplesProvider = args.getOrEmpty("examples", new TypeReference<String>() {});
    if (maybeExamplesProvider.isPresent()) {
      BaseExampleProvider provider = resolveExampleProvider(maybeExamplesProvider.get());
      return ExampleTool.builder().setExampleProvider(provider).build();
    }
    var maybeListOfExamples = args.getOrEmpty("examples", new TypeReference<List<Example>>() {});
    if (maybeListOfExamples.isPresent()) {
      var b = ExampleTool.builder();
      for (Example example : maybeListOfExamples.get()) {
        b.addExample(example);
      }
      return b.build();
    }

    throw new ConfigurationException(
        "ExampleTool requires 'examples' argument to be either an example provider name (String) or"
            + " a list of examples (List<Example>).");
  }

  /** Overload to match resolver which passes only ToolArgsConfig. */
  public static ExampleTool fromConfig(ToolArgsConfig args) throws ConfigurationException {
    return fromConfig(args, /* configAbsPath= */ "");
  }

  private static BaseExampleProvider resolveExampleProvider(String ref)
      throws ConfigurationException {
    int lastDot = ref.lastIndexOf('.');
    if (lastDot <= 0) {
      throw new ConfigurationException(
          "Invalid example provider reference: " + ref + ". Expected ClassName.FIELD");
    }
    String className = ref.substring(0, lastDot);
    String fieldName = ref.substring(lastDot + 1);
    try {
      Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
      Field field = clazz.getField(fieldName);
      if (!Modifier.isStatic(field.getModifiers())) {
        throw new ConfigurationException(
            "Field '" + fieldName + "' in class '" + className + "' is not static");
      }
      Object instance = field.get(null);
      if (instance instanceof BaseExampleProvider provider) {
        return provider;
      }
      throw new ConfigurationException(
          "Field '" + fieldName + "' in class '" + className + "' is not a BaseExampleProvider");
    } catch (NoSuchFieldException e) {
      throw new ConfigurationException(
          "Field '" + fieldName + "' not found in class '" + className + "'", e);
    } catch (ClassNotFoundException e) {
      throw new ConfigurationException("Example provider class not found: " + className, e);
    } catch (IllegalAccessException e) {
      throw new ConfigurationException("Cannot access example provider field: " + ref, e);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ExampleTool}. */
  public static final class Builder {
    private final List<Example> examples = new ArrayList<>();
    private String name = "example_tool";
    private String description = "Adds few-shot examples to the request";
    private Optional<BaseExampleProvider> provider = Optional.empty();

    @CanIgnoreReturnValue
    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setDescription(String description) {
      this.description = description;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addExample(Example ex) {
      this.examples.add(ex);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setExampleProvider(BaseExampleProvider provider) {
      this.provider = Optional.ofNullable(provider);
      return this;
    }

    public ExampleTool build() {
      return new ExampleTool(this);
    }
  }
}
