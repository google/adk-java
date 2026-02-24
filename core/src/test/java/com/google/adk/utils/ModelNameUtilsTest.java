package com.google.adk.utils;

import static com.google.common.truth.Truth.assertThat;

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
}
