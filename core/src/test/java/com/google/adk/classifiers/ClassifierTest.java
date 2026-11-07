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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.classifiers.Answer.BinaryAnswer;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassifierTest {

  private static final BinaryAnswer ANSWER = BinaryAnswer.builder().probability(0.75).build();

  @Test
  public void classify_answersEveryQuestionIdOfTheRequest() {
    ClassifierRequest request =
        new ClassifierRequest(
            "Our checkout page is down.",
            ImmutableMap.of(
                "urgent",
                Question.Binary.builder().build(),
                "outage",
                Question.Binary.builder().build()));

    ClassifierResponse response = new FixedClassifier().classify(request).blockingGet();

    assertThat(response.answers()).containsExactly("urgent", ANSWER, "outage", ANSWER).inOrder();
  }

  @Test
  public void name_isWhatTheImplementationReports() {
    assertThat(new FixedClassifier().name()).isEqualTo("fixed");
  }

  /** Answers every question with {@link #ANSWER}. */
  private static final class FixedClassifier implements Classifier {

    @Override
    public String name() {
      return "fixed";
    }

    @Override
    public Single<ClassifierResponse> classify(ClassifierRequest request) {
      ImmutableMap<String, BinaryAnswer> answers =
          request.questions().keySet().stream()
              .collect(toImmutableMap(Function.identity(), unused -> ANSWER));
      return Single.just(ClassifierResponse.builder().answers(answers).build());
    }
  }
}
