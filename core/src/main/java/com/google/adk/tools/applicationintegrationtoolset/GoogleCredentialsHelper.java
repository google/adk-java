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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;

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
