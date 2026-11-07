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
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.adk.classifiers.Question.Binary;
import com.google.adk.classifiers.Question.Choice;
import com.google.adk.classifiers.Question.Score;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class QuestionTest {

  private static final ClassifierContent.Text INVOICES = new ClassifierContent.Text("Invoices");
  private static final ClassifierContent.Text BUGS = new ClassifierContent.Text("Bugs");

  @Test
  public void choice_keepsItsInstructionsAndLabelsInOrder() {
    Choice choice =
        Choice.builder()
            .instructions("Route the ticket.")
            .criterion("billing", "Invoices")
            .criterion("technical")
            .build();

    assertThat(choice.instructions()).hasValue(new ClassifierContent.Text("Route the ticket."));
    assertThat(choice.criteria().keySet()).containsExactly("billing", "technical").inOrder();
    assertThat(choice.criteria()).containsEntry("billing", INVOICES);
    assertThat(choice.criteria()).containsEntry("technical", null);
  }

  @Test
  public void choice_jsonInstructionsAndDescriptions_areKept() {
    ClassifierContent.JsonObject task =
        new ClassifierContent.JsonObject(JsonNodeFactory.instance.objectNode().put("task", "x"));
    ClassifierContent.JsonArray examples =
        new ClassifierContent.JsonArray(JsonNodeFactory.instance.arrayNode().add("refund"));

    Choice choice =
        Choice.builder()
            .instructions(task)
            .criterion("billing", examples)
            .criterion("technical", BUGS)
            .build();

    assertThat(choice.instructions()).hasValue(task);
    assertThat(choice.criteria()).containsExactly("billing", examples, "technical", BUGS).inOrder();
  }

  @Test
  public void choice_criterionForALabelAlreadySet_replacesItsDescriptionInPlace() {
    Choice choice =
        Choice.builder()
            .criterion("billing")
            .criterion("technical")
            .criterion("billing", "Invoices")
            .build();

    assertThat(choice.criteria().keySet()).containsExactly("billing", "technical").inOrder();
    assertThat(choice.criteria()).containsEntry("billing", INVOICES);
  }

  @Test
  public void choice_typedCriteriaMap_isAccepted() {
    ImmutableMap<String, ClassifierContent.Text> typed =
        ImmutableMap.of("billing", INVOICES, "technical", BUGS);

    assertThat(Choice.builder().criteria(typed).build().criteria())
        .containsExactlyEntriesIn(typed)
        .inOrder();
  }

  @Test
  public void choice_nullContentInstructions_clearThem() {
    Choice choice =
        Choice.builder()
            .instructions("Route the ticket.")
            .instructions((ClassifierContent) null)
            .criterion("a")
            .criterion("b")
            .build();

    assertThat(choice.instructions()).isEmpty();
  }

  @Test
  public void choice_nullLabel_throwsIllegalArgumentException() {
    Map<String, ClassifierContent> criteria = new LinkedHashMap<>();
    criteria.put(null, INVOICES);
    criteria.put("technical", BUGS);
    Choice.Builder builder = Choice.builder().criteria(criteria);

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(thrown).hasMessageThat().isEqualTo("Choice criteria labels must not be null.");
  }

  @Test
  public void choice_criteria_areUnmodifiable() {
    Choice choice = Choice.builder().criterion("billing").criterion("technical").build();
    Map<String, ClassifierContent> criteria = choice.criteria();

    assertThrows(UnsupportedOperationException.class, criteria::clear);
  }

  @Test
  public void choice_builderUsedAfterBuild_leavesTheBuiltQuestionUnchanged() {
    Choice.Builder builder = Choice.builder().criterion("billing").criterion("technical");
    Choice built = builder.build();

    builder.criterion("extra", "Added later");

    assertThat(built.criteria()).doesNotContainKey("extra");
  }

  @Test
  public void binary_withNothingSet_hasNoInstructionsOrCriteria() {
    Binary binary = Binary.builder().build();

    assertThat(binary.instructions()).isEmpty();
    assertThat(binary.criteria()).isEmpty();
  }

  @Test
  public void binary_textInstructionsAndCriteria_areWrappedAsText() {
    Binary binary = Binary.builder().instructions("Is it urgent?").criteria("Blocks work.").build();

    assertThat(binary.instructions()).hasValue(new ClassifierContent.Text("Is it urgent?"));
    assertThat(binary.criteria()).hasValue(new ClassifierContent.Text("Blocks work."));
  }

  @Test
  public void score_keepsItsLevelsInOrder() {
    Score score =
        Score.builder().instructions("Urgency?").criteria("low", "medium", "high").build();

    assertThat(score.criteria())
        .containsExactly(
            new ClassifierContent.Text("low"),
            new ClassifierContent.Text("medium"),
            new ClassifierContent.Text("high"))
        .inOrder();
  }

  @Test
  public void score_contentLevels_areKept() {
    ClassifierContent.JsonObject high =
        new ClassifierContent.JsonObject(JsonNodeFactory.instance.objectNode().put("level", 2));
    ImmutableList<ClassifierContent> levels = ImmutableList.of(INVOICES, high);

    assertThat(Score.builder().criteria(levels).build().criteria()).isEqualTo(levels);
  }

  @Test
  public void tooFewLabelsOrLevels_throwIllegalArgumentException() {
    Choice.Builder noLabels = Choice.builder().criteria(ImmutableMap.of());
    Choice.Builder oneLabel = Choice.builder().criterion("only");
    Score.Builder noLevels = Score.builder().criteria(ImmutableList.of());
    Score.Builder oneLevel = Score.builder().criteria(ImmutableList.of(INVOICES));

    assertThrows(IllegalArgumentException.class, noLabels::build);
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, oneLabel::build);
    assertThrows(IllegalArgumentException.class, noLevels::build);
    assertThrows(IllegalArgumentException.class, oneLevel::build);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Choice requires at least 2 criteria labels, got 1.");
  }

  @Test
  public void missingCriteria_throwsIllegalStateException() {
    Choice.Builder choiceBuilder = Choice.builder();
    Score.Builder scoreBuilder = Score.builder();

    IllegalStateException choice = assertThrows(IllegalStateException.class, choiceBuilder::build);
    IllegalStateException score = assertThrows(IllegalStateException.class, scoreBuilder::build);

    assertThat(choice).hasMessageThat().isEqualTo("Choice.Builder requires criteria to be set.");
    assertThat(score).hasMessageThat().isEqualTo("Score.Builder requires criteria to be set.");
  }

  @Test
  public void score_nullLevel_throwsNullPointerException() {
    Score.Builder builder = Score.builder();
    List<ClassifierContent> levels = Arrays.asList(INVOICES, null);

    assertThrows(NullPointerException.class, () -> builder.criteria(levels));
  }

  @Test
  public void toBuilder_withEveryPropertySet_buildsAnEqualQuestion() {
    Choice choice =
        Choice.builder()
            .instructions(ClassifierContent.parse("{\"task\":\"triage\"}"))
            .criterion("billing", "Invoices")
            .criterion("technical")
            .build();
    Binary binary =
        Binary.builder().instructions("Deletes data?").criteria("Deletes user records.").build();
    Score score =
        Score.builder()
            .instructions("Urgency?")
            .criteria(ImmutableList.of(INVOICES, ClassifierContent.parse("[\"high\"]")))
            .build();

    assertThat(choice.toBuilder().build()).isEqualTo(choice);
    assertThat(binary.toBuilder().build()).isEqualTo(binary);
    assertThat(score.toBuilder().build()).isEqualTo(score);
  }
}
