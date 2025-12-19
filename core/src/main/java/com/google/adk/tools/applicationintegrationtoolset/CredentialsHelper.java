package com.google.adk.tools.applicationintegrationtoolset;

import com.google.auth.Credentials;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public interface CredentialsHelper {

  Credentials getGoogleCredentials(@Nullable String serviceAccountJson) throws IOException;

  public static HttpRequest.Builder populateHeaders(
      HttpRequest.Builder builder, Credentials credentials) throws IOException {
    for (Map.Entry<String, List<String>> entry : credentials.getRequestMetadata().entrySet()) {
      for (String value : entry.getValue()) {
        builder = builder.header(entry.getKey(), value);
      }
    }
    return builder;
  }
}
