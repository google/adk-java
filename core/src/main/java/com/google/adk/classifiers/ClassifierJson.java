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

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.google.adk.classifiers.Answer.BinaryAnswer;
import com.google.adk.classifiers.Answer.ChoiceAnswer;
import com.google.adk.classifiers.Answer.RefusalAnswer;
import com.google.adk.classifiers.Answer.ScoreAnswer;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes the classifier types in Python ADK's JSON form: a {@code type} field names the
 * kind of each question and answer, absent optional fields are written as null, and unknown fields
 * are rejected. Error messages name types and fields but never quote the JSON, which may be
 * untrusted.
 */
final class ClassifierJson {

  private static final String TYPE = "type";
  private static final String INSTRUCTIONS = "instructions";
  private static final String CRITERIA = "criteria";
  private static final String CHOICE = "choice";
  private static final String PROBABILITY = "probability";
  private static final String PROBABILITIES = "probabilities";
  private static final String SCORE = "score";
  private static final String CONFIDENCE = "confidence";
  private static final String ANSWERS = "answers";
  private static final String MODEL_VERSION = "model_version";

  private static final String CHOICE_TYPE = "choice";
  private static final String BINARY_TYPE = "binary";
  private static final String SCORE_TYPE = "score";
  private static final String REFUSAL_TYPE = "refusal";

  private static final ImmutableSet<String> QUESTION_FIELDS =
      ImmutableSet.of(TYPE, INSTRUCTIONS, CRITERIA);
  private static final ImmutableSet<String> CHOICE_ANSWER_FIELDS =
      ImmutableSet.of(TYPE, CHOICE, PROBABILITIES, CONFIDENCE);
  private static final ImmutableSet<String> BINARY_ANSWER_FIELDS =
      ImmutableSet.of(TYPE, PROBABILITY, CONFIDENCE);
  private static final ImmutableSet<String> SCORE_ANSWER_FIELDS =
      ImmutableSet.of(TYPE, SCORE, PROBABILITIES, CONFIDENCE);
  private static final ImmutableSet<String> REFUSAL_FIELDS = ImmutableSet.of(TYPE);
  private static final ImmutableSet<String> RESPONSE_FIELDS =
      ImmutableSet.of(ANSWERS, MODEL_VERSION);

  // An integer as JSON writes it: no plus sign and no leading zeros.
  private static final Pattern JSON_INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");

  private ClassifierJson() {}

  /** Writes a {@link Question}. */
  static final class QuestionSerializer extends StdSerializer<Question> {

    QuestionSerializer() {
      super(Question.class);
    }

    @Override
    public void serialize(Question question, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      generator.writeStartObject();
      if (question instanceof Question.Choice choice) {
        generator.writeStringField(TYPE, CHOICE_TYPE);
        writeInstructions(choice, generator);
        // Each entry is written by hand so that a mapper set to skip nulls keeps every label.
        generator.writeObjectFieldStart(CRITERIA);
        for (Map.Entry<String, @Nullable ClassifierContent> entry : choice.criteria().entrySet()) {
          generator.writeFieldName(entry.getKey());
          writeContent(entry.getValue(), generator);
        }
        generator.writeEndObject();
      } else if (question instanceof Question.Binary binary) {
        generator.writeStringField(TYPE, BINARY_TYPE);
        writeInstructions(binary, generator);
        generator.writeFieldName(CRITERIA);
        writeContent(binary.criteria().orElse(null), generator);
      } else {
        generator.writeStringField(TYPE, SCORE_TYPE);
        writeInstructions(question, generator);
        generator.writeArrayFieldStart(CRITERIA);
        for (ClassifierContent level : ((Question.Score) question).criteria()) {
          writeContent(level, generator);
        }
        generator.writeEndArray();
      }
      generator.writeEndObject();
    }

    private static void writeInstructions(Question question, JsonGenerator generator)
        throws IOException {
      generator.writeFieldName(INSTRUCTIONS);
      writeContent(question.instructions().orElse(null), generator);
    }
  }

  /** Writes {@code content} as a JSON string, object, or array, or null if absent. */
  private static void writeContent(@Nullable ClassifierContent content, JsonGenerator generator)
      throws IOException {
    if (content == null) {
      generator.writeNull();
    } else if (content instanceof ClassifierContent.Text text) {
      generator.writeString(text.text());
    } else if (content instanceof ClassifierContent.JsonObject object) {
      generator.writeTree(object.json());
    } else {
      generator.writeTree(((ClassifierContent.JsonArray) content).json());
    }
  }

  /** Writes an {@link Answer}. */
  static final class AnswerSerializer extends StdSerializer<Answer> {

    AnswerSerializer() {
      super(Answer.class);
    }

    @Override
    public void serialize(Answer answer, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      generator.writeStartObject();
      if (answer instanceof ChoiceAnswer choice) {
        generator.writeStringField(TYPE, CHOICE_TYPE);
        generator.writeStringField(CHOICE, choice.choice());
        provider.defaultSerializeField(PROBABILITIES, choice.probabilities(), generator);
        provider.defaultSerializeField(CONFIDENCE, choice.confidence().orElse(null), generator);
      } else if (answer instanceof BinaryAnswer binary) {
        generator.writeStringField(TYPE, BINARY_TYPE);
        generator.writeNumberField(PROBABILITY, binary.probability());
        provider.defaultSerializeField(CONFIDENCE, binary.confidence().orElse(null), generator);
      } else if (answer instanceof ScoreAnswer score) {
        generator.writeStringField(TYPE, SCORE_TYPE);
        generator.writeNumberField(SCORE, score.score());
        provider.defaultSerializeField(PROBABILITIES, score.probabilities(), generator);
        provider.defaultSerializeField(CONFIDENCE, score.confidence().orElse(null), generator);
      } else {
        generator.writeStringField(TYPE, REFUSAL_TYPE);
      }
      generator.writeEndObject();
    }
  }

  /** Writes a {@link ClassifierResponse}. */
  static final class ResponseSerializer extends StdSerializer<ClassifierResponse> {

    ResponseSerializer() {
      super(ClassifierResponse.class);
    }

    @Override
    public void serialize(
        ClassifierResponse response, JsonGenerator generator, SerializerProvider provider)
        throws IOException {
      generator.writeStartObject();
      provider.defaultSerializeField(ANSWERS, response.answers(), generator);
      provider.defaultSerializeField(
          MODEL_VERSION, response.modelVersion().orElse(null), generator);
      generator.writeEndObject();
    }
  }

  /** Reads any {@link Question}; its {@code type} field is required. */
  static final class QuestionDeserializer extends Reader<Question> {
    QuestionDeserializer() {
      super(Question.class, "question", ClassifierJson::readQuestion);
    }
  }

  /** Reads a {@link Question.Choice}; its {@code type} field is optional. */
  static final class ChoiceDeserializer extends Reader<Question.Choice> {
    ChoiceDeserializer() {
      super(Question.Choice.class, "choice question", ClassifierJson::readChoice);
    }
  }

  /** Reads a {@link Question.Binary}; its {@code type} field is optional. */
  static final class BinaryDeserializer extends Reader<Question.Binary> {
    BinaryDeserializer() {
      super(Question.Binary.class, "binary question", ClassifierJson::readBinary);
    }
  }

  /** Reads a {@link Question.Score}; its {@code type} field is optional. */
  static final class ScoreDeserializer extends Reader<Question.Score> {
    ScoreDeserializer() {
      super(Question.Score.class, "score question", ClassifierJson::readScore);
    }
  }

  /** Reads any {@link Answer}; its {@code type} field is required. */
  static final class AnswerDeserializer extends Reader<Answer> {
    AnswerDeserializer() {
      super(Answer.class, "answer", ClassifierJson::readAnswer);
    }
  }

  /** Reads a {@link ChoiceAnswer}; its {@code type} field is optional. */
  static final class ChoiceAnswerDeserializer extends Reader<ChoiceAnswer> {
    ChoiceAnswerDeserializer() {
      super(ChoiceAnswer.class, "choice answer", ClassifierJson::readChoiceAnswer);
    }
  }

  /** Reads a {@link BinaryAnswer}; its {@code type} field is optional. */
  static final class BinaryAnswerDeserializer extends Reader<BinaryAnswer> {
    BinaryAnswerDeserializer() {
      super(BinaryAnswer.class, "binary answer", ClassifierJson::readBinaryAnswer);
    }
  }

  /** Reads a {@link ScoreAnswer}; its {@code type} field is optional. */
  static final class ScoreAnswerDeserializer extends Reader<ScoreAnswer> {
    ScoreAnswerDeserializer() {
      super(ScoreAnswer.class, "score answer", ClassifierJson::readScoreAnswer);
    }
  }

  /** Reads a {@link RefusalAnswer}; its {@code type} field is optional. */
  static final class RefusalAnswerDeserializer extends Reader<RefusalAnswer> {
    RefusalAnswerDeserializer() {
      super(RefusalAnswer.class, "refusal answer", ClassifierJson::readRefusal);
    }
  }

  /** Reads a {@link ClassifierResponse}. */
  static final class ResponseDeserializer extends Reader<ClassifierResponse> {
    ResponseDeserializer() {
      super(ClassifierResponse.class, "classifier response", ClassifierJson::readResponse);
    }
  }

  /**
   * Reads one classifier type from a JSON object using a function that throws {@link
   * IllegalArgumentException} for an invalid value.
   */
  abstract static class Reader<T> extends StdDeserializer<T> {
    private final String kind;
    private final transient Function<JsonNode, T> read;

    Reader(Class<T> type, String kind, Function<JsonNode, T> read) {
      super(type);
      this.kind = kind;
      this.read = read;
    }

    @Override
    public final T deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode node;
      try {
        node = context.readTree(parser);
      } catch (StreamReadException unused) {
        // The parser's message can quote the malformed JSON, so the cause is dropped.
        throw invalid("Malformed " + kind + " JSON.", /* cause= */ null);
      }
      try {
        checkArgument(node.isObject(), "Expected %s JSON to be an object.", kind);
        return read.apply(node);
      } catch (IllegalArgumentException e) {
        throw invalid(e.getMessage(), e);
      }
    }

    /**
     * Builds the exception without a parser, because a parser's error location can include the
     * JSON.
     */
    private static JsonMappingException invalid(String message, @Nullable Throwable cause) {
      return new JsonMappingException(/* processor= */ null, message, cause);
    }
  }

  private static Question readQuestion(JsonNode node) {
    return switch (requireType(node)) {
      case CHOICE_TYPE -> readChoice(node);
      case BINARY_TYPE -> readBinary(node);
      case SCORE_TYPE -> readScore(node);
      default -> throw new IllegalArgumentException("Unsupported question type.");
    };
  }

  private static Question.Choice readChoice(JsonNode node) {
    checkFields(node, QUESTION_FIELDS, "choice question");
    checkType(node, CHOICE_TYPE, "choice question");
    JsonNode criteria = node.get(CRITERIA);
    checkArgument(
        criteria != null && criteria.isObject(), "A choice question requires a criteria object.");
    Map<String, @Nullable ClassifierContent> labels = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> field : criteria.properties()) {
      labels.put(field.getKey(), readContent(field.getValue()));
    }
    return Question.Choice.builder()
        .instructions(readContent(node.get(INSTRUCTIONS)))
        .criteria(labels)
        .build();
  }

  private static Question.Binary readBinary(JsonNode node) {
    checkFields(node, QUESTION_FIELDS, "binary question");
    checkType(node, BINARY_TYPE, "binary question");
    return Question.Binary.builder()
        .instructions(readContent(node.get(INSTRUCTIONS)))
        .criteria(readContent(node.get(CRITERIA)))
        .build();
  }

  private static Question.Score readScore(JsonNode node) {
    checkFields(node, QUESTION_FIELDS, "score question");
    checkType(node, SCORE_TYPE, "score question");
    JsonNode criteria = node.get(CRITERIA);
    checkArgument(
        criteria != null && criteria.isArray(), "A score question requires a criteria array.");
    List<ClassifierContent> levels = new ArrayList<>(criteria.size());
    for (JsonNode level : criteria) {
      checkArgument(!level.isNull(), "Score criteria must not contain null.");
      levels.add(readContent(level));
    }
    return Question.Score.builder()
        .instructions(readContent(node.get(INSTRUCTIONS)))
        .criteria(levels)
        .build();
  }

  private static Answer readAnswer(JsonNode node) {
    checkArgument(node.isObject(), "Each answer must be a JSON object.");
    String type = requireType(node);
    return switch (type) {
      case CHOICE_TYPE -> readChoiceAnswer(node);
      case BINARY_TYPE -> readBinaryAnswer(node);
      case SCORE_TYPE -> readScoreAnswer(node);
      case REFUSAL_TYPE -> readRefusal(node);
      default -> throw new IllegalArgumentException("Unsupported answer type.");
    };
  }

  private static ChoiceAnswer readChoiceAnswer(JsonNode node) {
    checkFields(node, CHOICE_ANSWER_FIELDS, "choice answer");
    checkType(node, CHOICE_TYPE, "choice answer");
    JsonNode choice = node.get(CHOICE);
    checkArgument(
        choice != null && choice.isTextual(), "Missing or non-string '%s' field.", CHOICE);
    Map<String, Double> probabilities = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> probability :
        requireProbabilities(node, "choice answer").properties()) {
      probabilities.put(probability.getKey(), readNumber(probability.getValue(), PROBABILITIES));
    }
    return ChoiceAnswer.builder()
        .choice(choice.textValue())
        .probabilities(probabilities)
        .confidence(readConfidence(node))
        .build();
  }

  private static BinaryAnswer readBinaryAnswer(JsonNode node) {
    checkFields(node, BINARY_ANSWER_FIELDS, "binary answer");
    checkType(node, BINARY_TYPE, "binary answer");
    return BinaryAnswer.builder()
        .probability(readNumber(node.get(PROBABILITY), PROBABILITY))
        .confidence(readConfidence(node))
        .build();
  }

  private static ScoreAnswer readScoreAnswer(JsonNode node) {
    checkFields(node, SCORE_ANSWER_FIELDS, "score answer");
    checkType(node, SCORE_TYPE, "score answer");
    Map<Integer, Double> probabilities = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> probability :
        requireProbabilities(node, "score answer").properties()) {
      probabilities.put(
          readLevel(probability.getKey()), readNumber(probability.getValue(), PROBABILITIES));
    }
    return ScoreAnswer.builder()
        .score(readNumber(node.get(SCORE), SCORE))
        .probabilities(probabilities)
        .confidence(readConfidence(node))
        .build();
  }

  private static RefusalAnswer readRefusal(JsonNode node) {
    checkFields(node, REFUSAL_FIELDS, "refusal answer");
    checkType(node, REFUSAL_TYPE, "refusal answer");
    return RefusalAnswer.INSTANCE;
  }

  private static ClassifierResponse readResponse(JsonNode node) {
    checkFields(node, RESPONSE_FIELDS, "classifier response");
    JsonNode answers = node.get(ANSWERS);
    checkArgument(
        answers != null && answers.isObject(), "A classifier response requires an answers object.");
    Map<String, Answer> read = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> answer : answers.properties()) {
      read.put(answer.getKey(), readAnswer(answer.getValue()));
    }
    JsonNode modelVersion = node.get(MODEL_VERSION);
    checkArgument(
        modelVersion == null || modelVersion.isNull() || modelVersion.isTextual(),
        "Non-string '%s' field.",
        MODEL_VERSION);
    return ClassifierResponse.builder()
        .answers(read)
        .modelVersion(modelVersion == null ? null : modelVersion.textValue())
        .build();
  }

  private static void checkFields(JsonNode node, ImmutableSet<String> allowed, String kind) {
    for (Map.Entry<String, JsonNode> field : node.properties()) {
      checkArgument(allowed.contains(field.getKey()), "Unknown field in %s JSON.", kind);
    }
  }

  /** Checks the optional {@code type} field of a concrete type. */
  private static void checkType(JsonNode node, String expected, String kind) {
    JsonNode type = node.get(TYPE);
    checkArgument(
        type == null || expected.equals(type.textValue()),
        "Expected '%s' as the %s type.",
        expected,
        kind);
  }

  private static String requireType(JsonNode node) {
    JsonNode type = node.get(TYPE);
    checkArgument(type != null && type.isTextual(), "Missing or non-string '%s' field.", TYPE);
    return type.textValue();
  }

  private static JsonNode requireProbabilities(JsonNode node, String kind) {
    JsonNode probabilities = node.get(PROBABILITIES);
    checkArgument(
        probabilities != null && probabilities.isObject(),
        "A %s requires a probabilities object.",
        kind);
    return probabilities;
  }

  private static double readNumber(@Nullable JsonNode value, String field) {
    checkArgument(value != null && value.isNumber(), "Expected a number for '%s'.", field);
    return value.doubleValue();
  }

  private static @Nullable Double readConfidence(JsonNode node) {
    JsonNode confidence = node.get(CONFIDENCE);
    if (confidence == null || confidence.isNull()) {
      return null;
    }
    return readNumber(confidence, CONFIDENCE);
  }

  /** Reads a score level, which JSON writes as an object key. */
  private static int readLevel(String key) {
    checkArgument(JSON_INTEGER.matcher(key).matches(), "ScoreAnswer levels must be integers.");
    Integer level = Ints.tryParse(key);
    checkArgument(level != null, "ScoreAnswer levels must fit in an int.");
    return level;
  }

  /**
   * Returns the {@link ClassifierContent} in {@code node}, or null if absent. The JSON object and
   * array constructors normalize the nodes, so the result does not depend on how the mapper that
   * parsed them is configured.
   */
  private static @Nullable ClassifierContent readContent(@Nullable JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return new ClassifierContent.Text(node.textValue());
    }
    if (node instanceof ObjectNode object) {
      return new ClassifierContent.JsonObject(object);
    }
    if (node instanceof ArrayNode array) {
      return new ClassifierContent.JsonArray(array);
    }
    throw new IllegalArgumentException("Expected string, object, or array JSON content.");
  }
}
