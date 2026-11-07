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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.JsonBaseModel;
import com.google.adk.classifiers.Answer.BinaryAnswer;
import com.google.adk.classifiers.Answer.ChoiceAnswer;
import com.google.adk.classifiers.Answer.RefusalAnswer;
import com.google.adk.classifiers.Answer.ScoreAnswer;
import com.google.adk.classifiers.Question.Binary;
import com.google.adk.classifiers.Question.Choice;
import com.google.adk.classifiers.Question.Score;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassifierJsonTest {

  private static final ObjectMapper mapper = JsonBaseModel.getMapper();
  private static final String SECRET = "secret-untrusted-token-xyz";

  private static final Choice CHOICE =
      Choice.builder()
          .instructions("Which team?")
          .criterion("billing", "Invoices")
          .criterion("technical")
          .build();
  private static final Binary BINARY = Binary.builder().criteria("Deletes data.").build();
  private static final Score SCORE =
      Score.builder()
          .instructions(
              ClassifierContent.parse(
                  "{\"scale\":2,\"strict\":true,\"enabled\":false,\"weight\":0.5}"))
          .criteria(
              ImmutableList.of(
                  ClassifierContent.of("low"),
                  ClassifierContent.parse("{\"level\":\"high\",\"note\":null}")))
          .build();
  private static final ImmutableMap<String, Question> QUESTIONS =
      ImmutableMap.of("route", CHOICE, "risky", BINARY, "urgency", SCORE);

  private static final ChoiceAnswer CHOICE_ANSWER =
      ChoiceAnswer.builder().choice("a").probabilities(ImmutableMap.of("a", 0.9, "b", 0.1)).build();
  private static final BinaryAnswer BINARY_ANSWER =
      BinaryAnswer.builder().probability(0.2).confidence(0.9).build();
  private static final ScoreAnswer SCORE_ANSWER =
      ScoreAnswer.builder()
          .score(0.75)
          .probabilities(ImmutableMap.of(0, 0.25, 1, 0.75))
          .confidence(0.5)
          .build();
  private static final ImmutableMap<String, Answer> ANSWERS =
      ImmutableMap.of(
          "route", CHOICE_ANSWER,
          "risky", BINARY_ANSWER,
          "urgency", SCORE_ANSWER,
          "refused", RefusalAnswer.INSTANCE);
  private static final ClassifierResponse RESPONSE =
      ClassifierResponse.builder().answers(ANSWERS).modelVersion("jev-1.13.0").build();
  private static final ClassifierResponse EMPTY_RESPONSE =
      ClassifierResponse.builder().answers(ImmutableMap.of()).build();

  @Test
  public void write_producesPythonJsonWithExplicitNulls() throws Exception {
    assertThat(mapper.writeValueAsString(CHOICE))
        .isEqualTo(
            json(
                "{'type':'choice','instructions':'Which team?',"
                    + "'criteria':{'billing':'Invoices','technical':null}}"));
    assertThat(mapper.writeValueAsString(BINARY))
        .isEqualTo(json("{'type':'binary','instructions':null,'criteria':'Deletes data.'}"));
    assertThat(mapper.writeValueAsString(SCORE))
        .isEqualTo(
            json(
                "{'type':'score',"
                    + "'instructions':{'scale':2,'strict':true,'enabled':false,'weight':0.5},"
                    + "'criteria':['low',{'level':'high','note':null}]}"));
    assertThat(mapper.writeValueAsString(RESPONSE))
        .isEqualTo(
            json(
                "{'answers':{"
                    + "'route':{'type':'choice','choice':'a','probabilities':{'a':0.9,'b':0.1},"
                    + "'confidence':null},"
                    + "'risky':{'type':'binary','probability':0.2,'confidence':0.9},"
                    + "'urgency':{'type':'score','score':0.75,'probabilities':{'0':0.25,'1':0.75},"
                    + "'confidence':0.5},"
                    + "'refused':{'type':'refusal'}},"
                    + "'model_version':'jev-1.13.0'}"));
    assertThat(mapper.writeValueAsString(EMPTY_RESPONSE))
        .isEqualTo(json("{'answers':{},'model_version':null}"));
  }

  @Test
  public void answerConvertedToAMap_hasTheShapeOfPythonModelDump() {
    Map<String, Object> answer =
        mapper.convertValue(CHOICE_ANSWER, new TypeReference<Map<String, Object>>() {});

    assertThat(answer)
        .containsExactly(
            "type",
            "choice",
            "choice",
            "a",
            "probabilities",
            ImmutableMap.of("a", 0.9, "b", 0.1),
            "confidence",
            null)
        .inOrder();
  }

  @Test
  public void writeAndRead_numberContent_roundTripsAsLongAndDouble() throws Exception {
    ObjectNode numbers = JsonNodeFactory.instance.objectNode().put("count", 2).put("ratio", 0.5f);
    Binary question =
        Binary.builder().instructions(new ClassifierContent.JsonObject(numbers)).build();

    String written = mapper.writeValueAsString(question);

    assertThat(written)
        .isEqualTo(
            json("{'type':'binary','instructions':{'count':2,'ratio':0.5},'criteria':null}"));
    assertThat(mapper.readValue(written, Binary.class)).isEqualTo(question);
  }

  @Test
  public void write_withAMapperThatSkipsNulls_keepsLabelsWithoutADescription() throws Exception {
    ObjectMapper skippingNulls = mapper.copy().setSerializationInclusion(Include.NON_NULL);
    Choice nestedNull =
        CHOICE.toBuilder().criterion("other", ClassifierContent.parse("{\"note\":null}")).build();

    String written = skippingNulls.writeValueAsString(nestedNull);

    assertThat(written)
        .isEqualTo(
            json(
                "{'type':'choice','instructions':'Which team?','criteria':{'billing':'Invoices',"
                    + "'technical':null,'other':{'note':null}}}"));
    assertThat(skippingNulls.readValue(written, Choice.class)).isEqualTo(nestedNull);
  }

  @Test
  public void read_withAMapperThatParsesBigIntegers_readsContentAsLongs() throws Exception {
    ObjectMapper bigIntegers =
        mapper.copy().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    Binary read =
        bigIntegers.readValue(
            json("{'type':'binary','instructions':{'n':1,'big':4294967296}}"), Binary.class);

    assertThat(read.instructions())
        .hasValue(ClassifierContent.parse("{\"n\":1,\"big\":4294967296}"));
  }

  @Test
  public void read_integerContentOutsideLongRange_throwsWithoutQuotingIt() {
    String raw = json("{'type':'binary','instructions':{'n':99999999999999999999}}");

    JsonMappingException thrown =
        assertThrows(JsonMappingException.class, () -> mapper.readValue(raw, Binary.class));

    assertThat(thrown).hasMessageThat().contains("JSON content integers must fit in a long.");
    assertThat(thrown).hasMessageThat().doesNotContain("99999999999999999999");
  }

  @Test
  public void roundTrip_throughTheInterfaces_keepsEveryValue() throws Exception {
    assertThat(roundTrip(QUESTIONS, new TypeReference<Map<String, Question>>() {}))
        .containsExactlyEntriesIn(QUESTIONS)
        .inOrder();
    assertThat(roundTrip(ANSWERS, new TypeReference<Map<String, Answer>>() {}))
        .containsExactlyEntriesIn(ANSWERS)
        .inOrder();
    assertThat(roundTrip(RESPONSE, new TypeReference<ClassifierResponse>() {})).isEqualTo(RESPONSE);
  }

  @Test
  public void roundTrip_throughTheConcreteTypes_keepsEveryValue() throws Exception {
    assertThat(roundTrip(CHOICE, new TypeReference<Choice>() {})).isEqualTo(CHOICE);
    assertThat(roundTrip(BINARY, new TypeReference<Binary>() {})).isEqualTo(BINARY);
    assertThat(roundTrip(SCORE, new TypeReference<Score>() {})).isEqualTo(SCORE);
    assertThat(roundTrip(CHOICE_ANSWER, new TypeReference<ChoiceAnswer>() {}))
        .isEqualTo(CHOICE_ANSWER);
    assertThat(roundTrip(BINARY_ANSWER, new TypeReference<BinaryAnswer>() {}))
        .isEqualTo(BINARY_ANSWER);
    assertThat(roundTrip(SCORE_ANSWER, new TypeReference<ScoreAnswer>() {}))
        .isEqualTo(SCORE_ANSWER);
    assertThat(roundTrip(RefusalAnswer.INSTANCE, new TypeReference<RefusalAnswer>() {}))
        .isSameInstanceAs(RefusalAnswer.INSTANCE);
    assertThat(roundTrip(EMPTY_RESPONSE, new TypeReference<ClassifierResponse>() {}))
        .isEqualTo(EMPTY_RESPONSE);
  }

  @Test
  public void read_omittedOptionalFieldsAndType_readAsAbsent() throws Exception {
    assertThat(mapper.readValue(json("{'criteria':{'a':null,'b':null}}"), Choice.class))
        .isEqualTo(Choice.builder().criterion("a").criterion("b").build());
    assertThat(mapper.readValue(json("{'type':'binary'}"), Question.class))
        .isEqualTo(Binary.builder().build());
    assertThat(mapper.readValue(json("{'probability':0.4}"), BinaryAnswer.class))
        .isEqualTo(BinaryAnswer.builder().probability(0.4).build());
    assertThat(mapper.readValue(json("{'answers':{}}"), ClassifierResponse.class))
        .isEqualTo(EMPTY_RESPONSE);
  }

  @Test
  public void roundTrip_contentNestedToTheLimit_keepsTheValue() throws Exception {
    Binary atLimit =
        Binary.builder()
            .instructions(nestedLists(ClassifierValidation.MAX_JSON_CONTENT_DEPTH))
            .build();

    assertThat(roundTrip(atLimit, new TypeReference<Binary>() {})).isEqualTo(atLimit);
  }

  @Test
  public void read_contentNestedDeeperThanTheLimit_throws() throws Exception {
    String atLimit =
        mapper.writeValueAsString(
            Binary.builder()
                .instructions(nestedLists(ClassifierValidation.MAX_JSON_CONTENT_DEPTH))
                .build());
    String overLimit = atLimit.replace("\"leaf\"", "[\"leaf\"]");

    assertThrows(JsonMappingException.class, () -> mapper.readValue(overLimit, Binary.class));
  }

  @Test
  public void read_unknownField_namesTheKindButNotTheField() {
    JsonMappingException thrown =
        assertThrows(
            JsonMappingException.class,
            () -> mapper.readValue(json("{'type':'binary','" + SECRET + "':1}"), Question.class));

    assertThat(thrown).hasMessageThat().contains("Unknown field in binary question JSON.");
    assertThat(thrown).hasMessageThat().doesNotContain(SECRET);
  }

  @Test
  public void read_invalidQuestions_throwWithoutQuotingTheJson() {
    assertAllRejected(
        Question.class,
        "{'type':'choice','instruction':'SECRET','criteria':{'a':null,'b':null}}",
        "{'type':'choice','criteria':{'SECRET':null}}",
        "{'type':'choice','criteria':null}",
        "{'type':'choice'}",
        "{'type':'score'}",
        "{'type':'choice','instructions':true,'criteria':{'a':null,'b':null}}",
        "{'type':'binary','criteria':123}",
        "{'type':'binary','SECRET':1}",
        "{'type':'score','criteria':['low']}",
        "{'type':'score','criteria':['low',null]}",
        "{'type':'score','criteria':['low',{'n':12345678901234567890}]}",
        "{'type':'score','criteria':['low',{'x':1e400}]}",
        "{'type':'score','criteria':{'a':'low','b':'high'}}",
        "{'type':'SECRET'}",
        "{'criteria':{'a':null,'b':null}}",
        "[]");
  }

  @Test
  public void read_malformedJson_throwsWithoutQuotingIt() {
    assertAllRejected(
        Binary.class, "{'type':'binary','criteria':'SECRET", "{'type':'binary','criteria':SECRET}");
  }

  @Test
  public void read_jsonThatIsNotAnObject_throws() {
    assertAllRejected(Binary.class, "[]", "'SECRET'", "5");
    assertAllRejected(RefusalAnswer.class, "[]");
    assertAllRejected(ClassifierResponse.class, "[]");
  }

  @Test
  public void read_concreteTypeWithAnotherTypeField_throws() {
    assertAllRejected(
        Choice.class,
        "{'type':'binary','criteria':{'a':null,'b':null}}",
        "{'type':null,'criteria':{'a':null,'b':null}}");
    assertAllRejected(Binary.class, "{'type':'score'}");
    assertAllRejected(Score.class, "{'type':null,'criteria':['a','b']}");
    assertAllRejected(RefusalAnswer.class, "{'type':'binary'}");
  }

  @Test
  public void read_scoreLevelOutsideIntRange_failsWithoutQuotingTheKey() {
    String raw = json("{'type':'score','score':0.0,'probabilities':{'999999999999':1.0}}");

    JsonMappingException thrown =
        assertThrows(JsonMappingException.class, () -> mapper.readValue(raw, Answer.class));

    assertThat(thrown).hasMessageThat().contains("ScoreAnswer levels must fit in an int.");
    for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
      assertThat(cause).hasMessageThat().doesNotContain("999999999999");
    }
  }

  @Test
  public void read_invalidAnswers_throwWithoutQuotingTheJson() {
    assertAllRejected(
        Answer.class,
        "{'type':'refusal','extra':'SECRET'}",
        "{'type':'choice','choice':'a','probabilities':{'a':'0.5'}}",
        "{'type':'choice','choice':'a','probabilities':{'a':1.5}}",
        "{'type':'choice','probabilities':{'a':1.0}}",
        "{'type':'choice','choice':'a','probabilities':['a']}",
        "{'type':'score','score':0.0,'probabilities':[1.0]}",
        "{'type':'binary','probability':-0.1}",
        "{'type':'binary','probability':0.5,'SECRET':1}",
        "{'type':'score','score':0.0,'probabilities':{'01':1.0}}",
        "{'type':'score','score':0.0,'probabilities':{'+1':1.0}}",
        "{'type':'SECRET'}",
        "{'probability':0.5}");
  }

  @Test
  public void read_invalidResponses_throwWithoutQuotingTheJson() {
    assertAllRejected(
        ClassifierResponse.class,
        "{'answers':{'SECRET':{'type':'choice','choice':'a','probabilities':{'SECRET':null}}}}",
        "{'answers':{'q':{'type':'binary','probability':-0.1}}}",
        "{'answers':{'q':{'type':'SECRET'}}}",
        "{'answers':{'SECRET':'not-an-object'}}",
        "{'answers':{},'modelVersion':'SECRET'}",
        "{'answers':{},'model_version':123}",
        "{'answers':[]}",
        "{'answers':'SECRET'}",
        "{'model_version':'m'}");
  }

  /**
   * Asserts that reading each of {@code singleQuotedJson}, with {@code SECRET} replaced by {@link
   * #SECRET}, fails without it in the message of the exception or of any cause.
   */
  private static void assertAllRejected(Class<?> type, String... singleQuotedJson) {
    for (int i = 0; i < singleQuotedJson.length; i++) {
      String raw = json(singleQuotedJson[i].replace("SECRET", SECRET));
      JsonMappingException thrown =
          assertThrows("case " + i, JsonMappingException.class, () -> mapper.readValue(raw, type));
      for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
        assertWithMessage("case %s", i).that(cause).hasMessageThat().doesNotContain(SECRET);
      }
    }
  }

  private static <T> T roundTrip(Object value, TypeReference<T> type) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(value), type);
  }

  /** Returns {@code singleQuoted} with its single quotes turned into double quotes. */
  private static String json(String singleQuoted) {
    return singleQuoted.replace('\'', '"');
  }

  private static ClassifierContent nestedLists(int depth) {
    ArrayNode nested = JsonNodeFactory.instance.arrayNode().add("leaf");
    for (int i = 1; i < depth; i++) {
      nested = JsonNodeFactory.instance.arrayNode().add(nested);
    }
    return new ClassifierContent.JsonArray(nested);
  }
}
