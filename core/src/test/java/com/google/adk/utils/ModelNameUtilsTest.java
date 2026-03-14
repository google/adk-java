package com.google.adk.utils;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.Gemini;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ModelNameUtilsTest {

  @Test
  public void isGemini2Model_withGemini2Model_returnsTrue() {
    assertThat(ModelNameUtils.isGemini2Model("gemini-2.5-flash")).isTrue();
  }

  @Test
  public void isGemini2Model_withNonGemini2Model_returnsFalse() {
    assertThat(ModelNameUtils.isGemini2Model("gemini-1.5-pro")).isFalse();
  }

  @Test
  public void isGemini2Model_withPathBasedGemini2Model_returnsTrue() {
    assertThat(
            ModelNameUtils.isGemini2Model(
                "projects/test-project/locations/us-central1/publishers/google/models/gemini-2.5-flash"))
        .isTrue();
  }

  @Test
  public void isGemini2Model_withPathBasedNonGemini2Model_returnsFalse() {
    assertThat(
            ModelNameUtils.isGemini2Model(
                "projects/test-project/locations/us-central1/publishers/google/models/gemini-1.5-pro"))
        .isFalse();
  }

  @Test
  public void isGemini2Model_withApigeeGemini2Model_returnsTrue() {
    assertThat(ModelNameUtils.isGemini2Model("apigee/gemini-2.5-flash")).isTrue();
  }

  @Test
  public void isGemini2Model_withApigeeV1Gemini2Model_returnsTrue() {
    assertThat(ModelNameUtils.isGemini2Model("apigee/v1/gemini-2.5-flash")).isTrue();
  }

  @Test
  public void isGemini2Model_withApigeeProviderGemini2Model_returnsTrue() {
    assertThat(ModelNameUtils.isGemini2Model("apigee/gemini/gemini-2.5-flash")).isTrue();
  }

  @Test
  public void isGemini2Model_withApigeeProviderVertexGemini2Model_returnsTrue() {
    assertThat(ModelNameUtils.isGemini2Model("apigee/vertex_ai/gemini-2.5-flash")).isTrue();
  }

  @Test
  public void isGemini2Model_withApigeeProviderV1Gemini2Model_returnsTrue() {
    assertThat(ModelNameUtils.isGemini2Model("apigee/gemini/v1/gemini-2.5-flash")).isTrue();
  }

  @Test
  public void isGemini2Model_withApigeeProviderV1BetaGemini2Model_returnsTrue() {
    assertThat(ModelNameUtils.isGemini2Model("apigee/vertex_ai/v1beta/gemini-2.5-flash")).isTrue();
  }

  @Test
  public void isGemini2Model_withNullModel_returnsFalse() {
    assertThat(ModelNameUtils.isGemini2Model(null)).isFalse();
  }

  @Test
  public void isGeminiModel_withGeminiModel_returnsTrue() {
    assertThat(ModelNameUtils.isGeminiModel("gemini-1.5-flash")).isTrue();
  }

  @Test
  public void isGeminiModel_withNonGeminiModel_returnsFalse() {
    assertThat(ModelNameUtils.isGeminiModel("text-bison")).isFalse();
  }

  @Test
  public void isGeminiModel_withPathBasedGeminiModel_returnsTrue() {
    assertThat(
            ModelNameUtils.isGeminiModel(
                "projects/test-project/locations/us-central1/publishers/google/models/gemini-1.5-pro"))
        .isTrue();
  }

  @Test
  public void isGeminiModel_withPathBasedNonGeminiModel_returnsFalse() {
    assertThat(
            ModelNameUtils.isGeminiModel(
                "projects/test-project/locations/us-central1/publishers/google/models/text-bison"))
        .isFalse();
  }

  @Test
  public void isGeminiModel_withApigeeGeminiModel_returnsTrue() {
    assertThat(ModelNameUtils.isGeminiModel("apigee/gemini-1.5-flash")).isTrue();
  }

  @Test
  public void isGeminiModel_withApigeeV1GeminiModel_returnsTrue() {
    assertThat(ModelNameUtils.isGeminiModel("apigee/v1/gemini-1.5-flash")).isTrue();
  }

  @Test
  public void isGeminiModel_withApigeeProviderGeminiModel_returnsTrue() {
    assertThat(ModelNameUtils.isGeminiModel("apigee/gemini/gemini-1.5-flash")).isTrue();
  }

  @Test
  public void isGeminiModel_withApigeeProviderVertexGeminiModel_returnsTrue() {
    assertThat(ModelNameUtils.isGeminiModel("apigee/vertex_ai/gemini-1.5-flash")).isTrue();
  }

  @Test
  public void isGeminiModel_withApigeeProviderV1GeminiModel_returnsTrue() {
    assertThat(ModelNameUtils.isGeminiModel("apigee/gemini/v1/gemini-1.5-flash")).isTrue();
  }

  @Test
  public void isGeminiModel_withApigeeProviderV1BetaGeminiModel_returnsTrue() {
    assertThat(ModelNameUtils.isGeminiModel("apigee/vertex_ai/v1beta/gemini-1.5-flash")).isTrue();
  }

  @Test
  public void isGeminiModel_withNullModel_returnsFalse() {
    assertThat(ModelNameUtils.isGeminiModel(null)).isFalse();
  }

  @Test
  public void isGeminiModel_withEmptyModel_returnsFalse() {
    assertThat(ModelNameUtils.isGeminiModel("")).isFalse();
  }

  @Test
  public void isInstanceOfGemini_withGeminiInstance_returnsTrue() {
    assertThat(ModelNameUtils.isInstanceOfGemini(new Gemini("", ""))).isTrue();
  }

  @Test
  public void isInstanceOfGemini_withNonGeminiInstance_returnsFalse() {
    assertThat(ModelNameUtils.isInstanceOfGemini(new Object())).isFalse();
  }

  @Test
  public void isInstanceOfGemini_withNullInstance_returnsFalse() {
    assertThat(ModelNameUtils.isInstanceOfGemini(null)).isFalse();
  }

  private static class GeminiSubclass extends Gemini {
    GeminiSubclass() {
      super("test-model", "test-api-key");
    }
  }

  private static class GeminiSubclassSubclass extends GeminiSubclass {}

  @Test
  public void isInstanceOfGemini_withGeminiSubclassInstance_returnsTrue() {
    assertThat(ModelNameUtils.isInstanceOfGemini(new GeminiSubclass())).isTrue();
  }

  @Test
  public void isInstanceOfGemini_withSubclassOfGeminiSubclassInstance_returnsTrue() {
    assertThat(ModelNameUtils.isInstanceOfGemini(new GeminiSubclassSubclass())).isTrue();
  }
}
