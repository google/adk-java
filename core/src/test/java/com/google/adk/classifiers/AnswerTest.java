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

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AnswerTest {

  @Test
  public void boundaryProbabilitiesAndConfidence_areAccepted() {
    BinaryAnswer lowest = BinaryAnswer.builder().probability(0.0).confidence(0.0).build();
    BinaryAnswer highest = BinaryAnswer.builder().probability(1.0).confidence(1.0).build();
    ChoiceAnswer choice =
        ChoiceAnswer.builder()
            .choice("a")
            .probabilities(ImmutableMap.of("a", 1.0, "b", 0.0))
            .confidence(1.0)
            .build();
    ScoreAnswer score =
        ScoreAnswer.builder()
            .score(1.0)
            .probabilities(ImmutableMap.of(0, 0.0, 1, 1.0))
            .confidence(0.0)
            .build();

    assertThat(lowest.probability()).isEqualTo(0.0);
    assertThat(highest.confidence()).hasValue(1.0);
    assertThat(choice.probabilities()).containsExactly("a", 1.0, "b", 0.0).inOrder();
    assertThat(score.probabilities()).containsExactly(0, 0.0, 1, 1.0).inOrder();
  }

  @Test
  public void confidence_isEmptyUnlessSet() {
    assertThat(BinaryAnswer.builder().probability(0.5).build().confidence()).isEmpty();
  }

  @Test
  public void probabilityOrConfidenceOutsideZeroToOne_throwsIllegalArgumentException() {
    for (double bad :
        new double[] {-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      BinaryAnswer.Builder binaryProbability = BinaryAnswer.builder().probability(bad);
      BinaryAnswer.Builder binaryConfidence =
          BinaryAnswer.builder().probability(0.5).confidence(bad);
      ChoiceAnswer.Builder choiceProbability =
          ChoiceAnswer.builder().choice("a").probabilities(ImmutableMap.of("a", bad));
      ChoiceAnswer.Builder choiceConfidence =
          ChoiceAnswer.builder()
              .choice("a")
              .probabilities(ImmutableMap.of("a", 1.0))
              .confidence(bad);
      ScoreAnswer.Builder scoreProbability =
          ScoreAnswer.builder().score(0.0).probabilities(ImmutableMap.of(0, bad));
      ScoreAnswer.Builder scoreConfidence =
          ScoreAnswer.builder().score(0.0).probabilities(ImmutableMap.of(0, 1.0)).confidence(bad);

      assertThrows(IllegalArgumentException.class, binaryProbability::build);
      assertThrows(IllegalArgumentException.class, binaryConfidence::build);
      assertThrows(IllegalArgumentException.class, choiceProbability::build);
      assertThrows(IllegalArgumentException.class, choiceConfidence::build);
      assertThrows(IllegalArgumentException.class, scoreProbability::build);
      assertThrows(IllegalArgumentException.class, scoreConfidence::build);
    }
  }

  @Test
  public void probabilityOutsideZeroToOne_messageNamesTheField() {
    ChoiceAnswer.Builder builder =
        ChoiceAnswer.builder().choice("a").probabilities(ImmutableMap.of("a", 1.5));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Each ChoiceAnswer probability must be a finite number in [0, 1].");
  }

  @Test
  public void scoreThatIsNotFinite_throwsIllegalArgumentException() {
    for (double bad :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      ScoreAnswer.Builder builder =
          ScoreAnswer.builder().score(bad).probabilities(ImmutableMap.of(0, 1.0));

      assertThrows(IllegalArgumentException.class, builder::build);
    }
  }

  @Test
  public void missingRequiredProperty_throwsIllegalStateException() {
    ChoiceAnswer.Builder choiceWithoutProbabilities = ChoiceAnswer.builder().choice("a");
    ChoiceAnswer.Builder choiceWithoutChoice =
        ChoiceAnswer.builder().probabilities(ImmutableMap.of("a", 1.0));
    BinaryAnswer.Builder binaryWithoutProbability = BinaryAnswer.builder();
    ScoreAnswer.Builder scoreWithoutProbabilities = ScoreAnswer.builder().score(0.0);
    ScoreAnswer.Builder scoreWithoutScore =
        ScoreAnswer.builder().probabilities(ImmutableMap.of(0, 1.0));

    assertThrows(IllegalStateException.class, choiceWithoutProbabilities::build);
    assertThrows(IllegalStateException.class, choiceWithoutChoice::build);
    assertThrows(IllegalStateException.class, binaryWithoutProbability::build);
    assertThrows(IllegalStateException.class, scoreWithoutProbabilities::build);
    assertThrows(IllegalStateException.class, scoreWithoutScore::build);
  }

  @Test
  public void toBuilder_withEveryPropertySet_buildsAnEqualAnswer() {
    ChoiceAnswer choice =
        ChoiceAnswer.builder()
            .choice("billing")
            .probabilities(ImmutableMap.of("billing", 0.9, "technical", 0.1))
            .confidence(0.85)
            .build();
    BinaryAnswer binary = BinaryAnswer.builder().probability(0.75).confidence(0.9).build();
    ScoreAnswer score =
        ScoreAnswer.builder()
            .score(1.25)
            .probabilities(ImmutableMap.of(0, 0.25, 1, 0.25, 2, 0.5))
            .confidence(0.8)
            .build();

    assertThat(choice.toBuilder().build()).isEqualTo(choice);
    assertThat(binary.toBuilder().build()).isEqualTo(binary);
    assertThat(score.toBuilder().build()).isEqualTo(score);
  }
}
