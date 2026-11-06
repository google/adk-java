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
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassifyRequestTest {

  private static final ImmutableMap<String, Binary> QUESTIONS =
      ImmutableMap.of(
          "urgent", Binary.builder().instructions("Urgent?").build(),
          "risky", Binary.builder().instructions("Risky?").build());

  @Test
  public void convenienceConstructors_equalTheCanonicalConstructor() {
    Content content = Content.fromParts(Part.fromText("ticket"));
    ClassifyRequest canonical =
        new ClassifyRequest(
            new ClassifierInput.Text("ticket"), ImmutableMap.<String, Question>copyOf(QUESTIONS));

    assertThat(new ClassifyRequest("ticket", QUESTIONS)).isEqualTo(canonical);
    assertThat(new ClassifyRequest(ClassifierInput.of("ticket"), QUESTIONS)).isEqualTo(canonical);
    assertThat(new ClassifyRequest(content, QUESTIONS))
        .isEqualTo(new ClassifyRequest(new ClassifierInput.Content(content), QUESTIONS));
  }

  @Test
  public void questions_keepTheirIdsInOrder() {
    ClassifyRequest request = new ClassifyRequest("ticket", QUESTIONS);

    assertThat(request.questions()).containsExactlyEntriesIn(QUESTIONS).inOrder();
  }

  @Test
  public void noQuestions_throwsIllegalArgumentException() {
    ImmutableMap<String, Question> noQuestions = ImmutableMap.of();

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new ClassifyRequest("ticket", noQuestions));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("ClassifyRequest requires at least one question.");
  }

  @Test
  public void nullQuestion_throwsNullPointerException() {
    Map<String, Question> questions = new HashMap<>();
    questions.put("urgent", null);

    assertThrows(NullPointerException.class, () -> new ClassifyRequest("ticket", questions));
  }
}
