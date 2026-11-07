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

import com.google.adk.classifiers.Answer.BinaryAnswer;
import com.google.adk.classifiers.Answer.RefusalAnswer;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassifierResponseTest {

  private static final BinaryAnswer URGENT = BinaryAnswer.builder().probability(0.75).build();

  @Test
  public void builder_typedAnswerMap_keepsTheAnswersAndModelVersion() {
    ImmutableMap<String, BinaryAnswer> onlyBinary = ImmutableMap.of("urgent", URGENT);

    ClassifierResponse response =
        ClassifierResponse.builder().answers(onlyBinary).modelVersion("jev-1.13.0").build();

    assertThat(response.answers()).containsExactly("urgent", URGENT);
    assertThat(response.modelVersion()).hasValue("jev-1.13.0");
  }

  @Test
  public void modelVersion_isEmptyUnlessSet() {
    ClassifierResponse response =
        ClassifierResponse.builder().answers(ImmutableMap.of("urgent", URGENT)).build();

    assertThat(response.modelVersion()).isEmpty();
  }

  @Test
  public void refusal_isOneAnswerAmongOthers() {
    ClassifierResponse response =
        ClassifierResponse.builder()
            .answers(ImmutableMap.of("urgent", URGENT, "declined", RefusalAnswer.INSTANCE))
            .build();

    assertThat(response.answers().get("urgent")).isEqualTo(URGENT);
    assertThat(response.answers().get("declined")).isSameInstanceAs(RefusalAnswer.INSTANCE);
  }

  @Test
  public void missingAnswers_throwsIllegalStateException() {
    ClassifierResponse.Builder builder = ClassifierResponse.builder();

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void toBuilder_withEveryPropertySet_buildsAnEqualResponse() {
    ClassifierResponse response =
        ClassifierResponse.builder()
            .answers(ImmutableMap.of("urgent", URGENT, "declined", RefusalAnswer.INSTANCE))
            .modelVersion("jev-1.13.0")
            .build();

    assertThat(response.toBuilder().build()).isEqualTo(response);
  }
}
