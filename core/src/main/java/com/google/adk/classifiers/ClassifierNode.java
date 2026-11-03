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

package com.google.adk.classifiers;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.workflow.BaseNode;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.NodeConfig;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.WorkflowConfigurationException;
import com.google.adk.workflow.WorkflowDataException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A workflow node that routes on a classifier's answer to one question about the node's input: the
 * label a {@link Choice} answer chose, or {@code true} or {@code false} for a {@link Binary}
 * answer, decided by {@link #threshold()}. The node's output is its unchanged input, so the node it
 * routes to receives what the classifier saw.
 *
 * <p>If the node has routed outgoing edges, building the workflow checks that every route is a
 * possible answer and that every possible answer has an edge, unless a default or unconditional
 * edge catches the rest.
 */
@Experimental
public final class ClassifierNode extends BaseNode {

  /** The ID the node asks its question under, as in Python ADK. */
  private static final String QUESTION_ID = "route";

  private static final double DEFAULT_THRESHOLD = 0.5;

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final BaseClassifier classifier;
  private final Question question;
  private final double threshold;
  private final @Nullable String answerKey;

  private ClassifierNode(Builder builder) {
    super(
        builder.name,
        builder.description,
        /* rerunOnResume= */ false,
        /* waitForOutput= */ false,
        builder.config,
        builder.inputSchema,
        builder.outputSchema,
        /* stateSchema= */ null);
    validateNodeName(name());
    this.classifier = builder.classifier;
    this.question = builder.question;
    this.threshold = builder.threshold != null ? builder.threshold : DEFAULT_THRESHOLD;
    this.answerKey = builder.answerKey;
    checkQuestion(builder.threshold);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Returns the classifier that answers {@link #question()}. */
  public BaseClassifier classifier() {
    return classifier;
  }

  /** Returns the {@link Choice} or {@link Binary} question whose answer picks the route. */
  public Question question() {
    return question;
  }

  /** Returns the probability at or above which a {@link Binary} question routes {@code true}. */
  public double threshold() {
    return threshold;
  }

  /**
   * Returns the state key under which the node stores the answer, in its JSON form as a map, if
   * set.
   */
  public Optional<String> answerKey() {
    return Optional.ofNullable(answerKey);
  }

  /**
   * Classifies {@code nodeInput} and outputs it with the route. Fails with {@link
   * WorkflowDataException} if the input is missing or has no JSON form, or if the answer is
   * missing, a refusal, a label outside the question's labels, or of the wrong type.
   */
  @Override
  public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
    return Single.defer(
            () ->
                classifier.classify(
                    new ClassifyRequest(
                        toClassifierInput(nodeInput), ImmutableMap.of(QUESTION_ID, question))))
        .map(
            response -> {
              Answer answer = response.answers().get(QUESTION_ID);
              if (answer == null) {
                throw new WorkflowDataException(
                    String.format("Classifier for node '%s' returned no answer.", name()));
              }
              Route route = routeFor(answer);
              if (answerKey != null) {
                var unused =
                    context
                        .state()
                        .put(answerKey, JsonBaseModel.getMapper().convertValue(answer, MAP_TYPE));
              }
              return routedEvent(context.invocationContext(), nodeInput, route);
            })
        .toFlowable();
  }

  @Override
  protected Optional<String> findOutgoingEdgeProblem(List<Edge> edges) {
    ImmutableSet<Route> edgeRoutes =
        edges.stream().flatMap(edge -> edge.routes().stream()).collect(toImmutableSet());
    if (edgeRoutes.isEmpty()) {
      return Optional.empty();
    }
    ImmutableSet<Route> routes =
        question instanceof Choice choice
            ? choice.criteria().keySet().stream().map(Route.Tag::new).collect(toImmutableSet())
            : ImmutableSet.of(new Route.Flag(true), new Route.Flag(false));
    Set<Route> unknown =
        Sets.difference(
            Sets.difference(edgeRoutes, routes), ImmutableSet.of(Route.Default.INSTANCE));
    if (!unknown.isEmpty()) {
      return Optional.of(
          String.format(
              "ClassifierNode '%s' has edges for routes it never emits: %s. Its routes are %s.",
              name(), labels(unknown), labels(routes)));
    }
    Set<Route> missing = Sets.difference(routes, edgeRoutes);
    // An unconditional edge always fires, so a route without an edge of its own continues there.
    if (!missing.isEmpty()
        && !edgeRoutes.contains(Route.Default.INSTANCE)
        && edges.stream().noneMatch(Edge::isUnconditional)) {
      return Optional.of(
          String.format(
              "ClassifierNode '%s' has no edge for routes %s, so the branch would end when the"
                  + " classifier picks one. Add an edge for each, or a default or unconditional"
                  + " edge.",
              name(), labels(missing)));
    }
    return Optional.empty();
  }

  private void checkQuestion(@Nullable Double requestedThreshold) {
    if (question instanceof Score) {
      throw new WorkflowConfigurationException(
          String.format(
              "ClassifierNode '%s' cannot route on a Score question; use a Choice or a Binary.",
              name()));
    }
    if (question instanceof Choice choice) {
      if (requestedThreshold != null) {
        throw new WorkflowConfigurationException(
            String.format(
                "ClassifierNode '%s' takes a threshold only with a Binary question; a Choice"
                    + " always routes to the label the classifier chose.",
                name()));
      }
      if (choice.criteria().containsKey(Route.DEFAULT_ROUTE_SENTINEL)) {
        throw new WorkflowConfigurationException(
            String.format(
                "ClassifierNode '%s' cannot route on the label \"%s\", which is reserved for the"
                    + " default route.",
                name(), Route.DEFAULT_ROUTE_SENTINEL));
      }
    } else if (requestedThreshold != null
        && !(requestedThreshold >= 0.0 && requestedThreshold <= 1.0)) {
      throw new WorkflowConfigurationException(
          String.format("ClassifierNode '%s' needs a threshold in [0, 1].", name()));
    }
  }

  private ClassifierInput toClassifierInput(@Nullable Object nodeInput) {
    if (nodeInput == null) {
      throw new WorkflowDataException(
          String.format("ClassifierNode '%s' received no input to classify.", name()));
    }
    if (nodeInput instanceof Content content) {
      return new ClassifierInput.Content(content);
    }
    if (nodeInput instanceof String text) {
      return new ClassifierInput.Text(text);
    }
    Object json =
        nodeInput instanceof Map || nodeInput instanceof List ? nodeInput : jsonForm(nodeInput);
    try {
      if (json instanceof Map<?, ?> map) {
        return ClassifierInput.of(ClassifierValidation.jsonObject(map));
      }
      if (json instanceof List<?> list) {
        return ClassifierInput.of(list);
      }
    } catch (IllegalArgumentException e) {
      throw new WorkflowDataException(
          String.format("ClassifierNode '%s' received an input that is not JSON content.", name()),
          e);
    }
    throw new WorkflowDataException(
        String.format(
            "ClassifierNode '%s' requires a String, Map, List, Content, or object that the JSON"
                + " mapper writes as an object or array, but got %s.",
            name(), ClassifierValidation.typeName(nodeInput)));
  }

  private Route routeFor(Answer answer) {
    if (answer instanceof RefusalAnswer) {
      throw new WorkflowDataException(
          String.format(
              "Classifier for node '%s' declined to answer, so the node has no route to take.",
              name()));
    }
    if (question instanceof Choice choice && answer instanceof ChoiceAnswer chosen) {
      // The label is model output, so the message leaves it out.
      if (!choice.criteria().containsKey(chosen.choice())) {
        throw new WorkflowDataException(
            String.format(
                "Classifier for node '%s' chose a label that is not one of its question's labels.",
                name()));
      }
      return new Route.Tag(chosen.choice());
    }
    if (question instanceof Binary && answer instanceof BinaryAnswer binary) {
      return new Route.Flag(binary.probability() >= threshold);
    }
    throw new WorkflowDataException(
        String.format(
            "Classifier for node '%s' returned a %s to a %s question.",
            name(), answerKind(answer), question instanceof Choice ? "Choice" : "Binary"));
  }

  private static Event routedEvent(
      InvocationContext invocationContext, @Nullable Object output, Route route) {
    return Event.builder()
        .id(invocationContext.newUuid())
        .timestamp(invocationContext.now().toEpochMilli())
        .author("")
        .output(output)
        .actions(EventActions.builder().route(ImmutableList.of(route)).build())
        .build();
  }

  /**
   * Returns the JSON form of a record or other object, which the node classifies because Python ADK
   * classifies a Pydantic model. Writing the value as text first converts dates to ISO-8601 strings
   * and big decimals to doubles.
   */
  private Object jsonForm(Object input) {
    ObjectMapper mapper = JsonBaseModel.getMapper();
    try {
      String json =
          mapper
              .writer()
              .without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
              .writeValueAsString(input);
      return mapper.readValue(json, Object.class);
    } catch (JsonProcessingException e) {
      throw new WorkflowDataException(
          String.format("ClassifierNode '%s' could not write its input as JSON.", name()), e);
    }
  }

  /** Returns the type of an answer other than a refusal, which the node rejects beforehand. */
  private static String answerKind(Answer answer) {
    if (answer instanceof ChoiceAnswer) {
      return "ChoiceAnswer";
    }
    return answer instanceof BinaryAnswer ? "BinaryAnswer" : "ScoreAnswer";
  }

  /**
   * Returns routes as a sorted list of their JSON forms, which distinguishes the label "true" from
   * true.
   */
  private static ImmutableList<String> labels(Set<Route> routes) {
    return routes.stream().map(JsonBaseModel::toJsonString).sorted().collect(toImmutableList());
  }

  /** Builder for {@link ClassifierNode}. The name, classifier and question are required. */
  public static final class Builder {
    private @Nullable String name;
    private @Nullable BaseClassifier classifier;
    private @Nullable Question question;
    private @Nullable Double threshold;
    private @Nullable String answerKey;
    private String description = "";
    private NodeConfig config = NodeConfig.builder().build();
    private @Nullable Schema inputSchema;
    private @Nullable Schema outputSchema;

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = checkNotNull(name);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder classifier(BaseClassifier classifier) {
      this.classifier = checkNotNull(classifier);
      return this;
    }

    /** Sets the {@link Choice} or {@link Binary} question whose answer picks the route. */
    @CanIgnoreReturnValue
    public Builder question(Question question) {
      this.question = checkNotNull(question);
      return this;
    }

    /**
     * Sets the probability in [0, 1] at or above which a {@link Binary} question routes {@code
     * true}, 0.5 if unset. A {@link Choice} question takes no threshold.
     */
    @CanIgnoreReturnValue
    public Builder threshold(double threshold) {
      this.threshold = threshold;
      return this;
    }

    /** Sets the state key under which the node stores the answer; null stores nothing. */
    @CanIgnoreReturnValue
    public Builder answerKey(@Nullable String answerKey) {
      this.answerKey = answerKey;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder description(String description) {
      this.description = checkNotNull(description);
      return this;
    }

    /** Sets the node's retry policy and execution timeout. */
    @CanIgnoreReturnValue
    public Builder config(NodeConfig config) {
      this.config = checkNotNull(config);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder inputSchema(@Nullable Schema inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder outputSchema(@Nullable Schema outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    /**
     * Builds the node.
     *
     * @throws IllegalStateException if the name, classifier or question is not set
     * @throws IllegalArgumentException if the name is empty or contains {@code /}, {@code @}, or
     *     {@code .}
     * @throws WorkflowConfigurationException if the question is a {@link Score} or has the label
     *     {@link Route#DEFAULT_ROUTE_SENTINEL}, or if a threshold is set for a {@link Choice} or
     *     lies outside [0, 1]
     */
    public ClassifierNode build() {
      checkState(name != null, "ClassifierNode.Builder requires name to be set.");
      checkState(classifier != null, "ClassifierNode.Builder requires classifier to be set.");
      checkState(question != null, "ClassifierNode.Builder requires question to be set.");
      return new ClassifierNode(this);
    }
  }
}
