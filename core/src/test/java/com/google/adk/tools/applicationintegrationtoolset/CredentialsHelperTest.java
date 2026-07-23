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

package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class CredentialsHelperTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private Credentials mockCredentials;

  @Test
  public void populateHeaders_success() throws Exception {
    when(mockCredentials.getRequestMetadata())
        .thenReturn(
            ImmutableMap.of(
                "header1", ImmutableList.of("header1_value1", "header1_value2"),
                "header2", ImmutableList.of("header2_value1")));
    HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("http://example.com"));
    builder = CredentialsHelper.populateHeaders(builder, mockCredentials);

    assertThat(builder.build().headers().allValues("header1"))
        .containsExactly("header1_value1", "header1_value2");
    assertThat(builder.build().headers().allValues("header2")).containsExactly("header2_value1");
  }
}
