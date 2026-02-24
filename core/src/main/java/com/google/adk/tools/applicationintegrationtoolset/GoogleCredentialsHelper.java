package com.google.adk.tools.applicationintegrationtoolset;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

public final class GoogleCredentialsHelper implements CredentialsHelper {

  @Override
  public GoogleCredentials getGoogleCredentials(@Nullable String serviceAccountJson)
      throws IOException {
    GoogleCredentials credentials;

    if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
      try (InputStream is = new ByteArrayInputStream(serviceAccountJson.getBytes(UTF_8))) {
        credentials = ServiceAccountCredentials.fromStream(is);
      }
    } else {
      credentials = GoogleCredentials.getApplicationDefault();
    }
    credentials = credentials.createScoped("https://www.googleapis.com/auth/cloud-platform");
    credentials.refreshIfExpired();
    return credentials;
  }
}
