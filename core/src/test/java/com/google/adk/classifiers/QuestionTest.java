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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class QuestionTest {

  private static final String SECRET = "s3cret-payload";

  @Test
  public void choice_keepsItsInstructionsAndLabelsInOrder() {
    Choice choice =
        Choice.builder()
            .instructions("Route the ticket.")
            .criterion("billing", "Invoices")
            .criterion("technical")
            .build();

    assertThat(choice.instructions()).hasValue("Route the ticket.");
    assertThat(choice.criteria().keySet()).containsExactly("billing", "technical").inOrder();
    assertThat(choice.criteria()).containsEntry("billing", "Invoices");
    assertThat(choice.criteria()).containsEntry("technical", null);
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
    assertThat(choice.criteria()).containsEntry("billing", "Invoices");
  }

  @Test
  public void choice_typedCriteriaMap_isAccepted() {
    ImmutableMap<String, String> typed =
        ImmutableMap.of("billing", "Invoices", "technical", "Bugs");

    assertThat(Choice.builder().criteria(typed).build().criteria())
        .containsExactlyEntriesIn(typed)
        .inOrder();
  }

  @Test
  public void choice_labelThatIsNotAString_throwsIllegalArgumentException() {
    Choice.Builder builder = Choice.builder().criteria(rawMap(1, "Invoices", 2, "Bugs"));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Choice criteria labels must be strings, got Integer.");
  }

  @Test
  public void choice_criteria_areUnmodifiable() {
    Choice choice = Choice.builder().criterion("billing").criterion("technical").build();
    Map<String, Object> criteria = choice.criteria();

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
  public void score_keepsItsLevelsInOrder() {
    Score score =
        Score.builder()
            .instructions("Urgency?")
            .criteria(ImmutableList.of("low", "medium", "high"))
            .build();

    assertThat(score.criteria()).containsExactly("low", "medium", "high").inOrder();
  }

  @Test
  public void jsonContent_acceptsEveryKindOfJsonValue() {
    ImmutableMap<String, Object> instructions =
        ImmutableMap.of(
            "task",
            "route",
            "values",
            Arrays.asList(1, 2L, (short) 3, (byte) 4, 0.5, 0.5f, true, null),
            "nested",
            ImmutableMap.of("levels", ImmutableList.of("low", "high")));

    assertThat(Binary.builder().instructions(instructions).build().instructions())
        .hasValue(instructions);
  }

  @Test
  public void jsonContent_isCopiedSoLaterChangesDoNotReachTheQuestion() {
    List<Object> levels = new ArrayList<>(ImmutableList.of("low"));
    Map<String, Object> instructions = new LinkedHashMap<>();
    instructions.put("levels", levels);

    Binary binary = Binary.builder().instructions(instructions).build();
    levels.add("high");
    instructions.put("extra", true);

    assertThat(binary.instructions()).hasValue(ImmutableMap.of("levels", ImmutableList.of("low")));
  }

  @Test
  public void tooFewLabelsOrLevels_throwIllegalArgumentException() {
    Choice.Builder noLabels = Choice.builder().criteria(ImmutableMap.of());
    Choice.Builder oneLabel = Choice.builder().criterion("only");
    Score.Builder noLevels = Score.builder().criteria(ImmutableList.of());
    Score.Builder oneLevel = Score.builder().criteria(ImmutableList.of("only"));

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
    List<String> levels = Arrays.asList("low", null);

    assertThrows(NullPointerException.class, () -> builder.criteria(levels));
  }

  @Test
  public void invalidJsonContent_inAnyField_throwsWithoutQuotingIt() {
    Content thought =
        Content.builder()
            .role("model")
            .parts(Part.builder().text(SECRET).thought(true).build())
            .build();
    ImmutableList<Object> invalid =
        ImmutableList.of(
            42,
            true,
            3.14,
            new byte[] {1, 2, 3},
            ImmutableSet.of(SECRET),
            ImmutableMap.of("tags", ImmutableSet.of(SECRET)),
            ImmutableMap.of(1, SECRET),
            ImmutableMap.of("nan", Double.NaN),
            ImmutableMap.of("inf", Double.POSITIVE_INFINITY),
            ImmutableList.of(Float.NaN),
            ImmutableList.of(Float.NEGATIVE_INFINITY),
            ImmutableList.of(BigInteger.ONE),
            thought,
            Part.fromText(SECRET),
            ImmutableMap.of("history", ImmutableList.of(thought)));

    for (Object bad : invalid) {
      assertRejected(
          () -> Choice.builder().instructions(bad).criterion("a").criterion("b").build());
      assertRejected(() -> Choice.builder().criterion("a", bad).criterion("b").build());
      assertRejected(() -> Binary.builder().instructions(bad).build());
      assertRejected(() -> Binary.builder().criteria(bad).build());
      assertRejected(
          () -> Score.builder().instructions(bad).criteria(ImmutableList.of("a", "b")).build());
      assertRejected(() -> Score.builder().criteria(ImmutableList.of("low", bad)).build());
    }
  }

  @Test
  public void jsonContent_nestedToTheLimit_isAcceptedAndDeeper_throws() {
    Object atLimit = "leaf";
    for (int i = 0; i < ClassifierValidation.MAX_JSON_CONTENT_DEPTH; i++) {
      atLimit = ImmutableList.of(atLimit);
    }
    Object instructions = atLimit;

    assertThat(Binary.builder().instructions(instructions).build().instructions())
        .hasValue(instructions);
    Binary.Builder overLimit = Binary.builder().instructions(ImmutableList.of(instructions));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, overLimit::build);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("JSON content exceeds maximum nesting depth of 200.");
  }

  @Test
  public void toBuilder_withEveryPropertySet_buildsAnEqualQuestion() {
    Choice choice =
        Choice.builder()
            .instructions(ImmutableMap.of("task", "triage"))
            .criterion("billing", "Invoices")
            .criterion("technical")
            .build();
    Binary binary =
        Binary.builder().instructions("Deletes data?").criteria("Deletes user records.").build();
    Score score =
        Score.builder()
            .instructions("Urgency?")
            .criteria(ImmutableList.of("low", ImmutableMap.of("level", "high")))
            .build();

    assertThat(choice.toBuilder().build()).isEqualTo(choice);
    assertThat(binary.toBuilder().build()).isEqualTo(binary);
    assertThat(score.toBuilder().build()).isEqualTo(score);
  }

  private static void assertRejected(ThrowingRunnable build) {
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, build);
    assertThat(thrown).hasMessageThat().doesNotContain(SECRET);
  }

  // A raw map bypasses the declared String key type, as a caller with an unchecked map can.
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Map<String, Object> rawMap(
      Object key1, Object value1, Object key2, Object value2) {
    return (Map) ImmutableMap.of(key1, value1, key2, value2);
  }
}
