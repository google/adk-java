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
