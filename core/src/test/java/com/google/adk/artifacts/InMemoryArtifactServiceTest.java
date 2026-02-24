/*
 * Copyright 2025 Google LLC
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
package com.google.adk.artifacts;

import static com.google.common.truth.Truth.assertThat;

import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InMemoryArtifactService}. */
@RunWith(JUnit4.class)
public class InMemoryArtifactServiceTest {

  private static final String APP_NAME = "test-app";
  private static final String USER_ID = "test-user";
  private static final String SESSION_ID = "test-session";
  private static final String FILENAME = "test-file.txt";

  private InMemoryArtifactService service;

  @Before
  public void setUp() {
    service = new InMemoryArtifactService();
  }

  @Test
  public void saveArtifact_savesAndReturnsVersion() {
    Part artifact = Part.fromBytes(new byte[] {1, 2, 3}, "text/plain");
    int version =
        service.saveArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact).blockingGet();
    assertThat(version).isEqualTo(0);
  }

  @Test
  public void loadArtifact_loadsLatest() {
    Part artifact1 = Part.fromBytes(new byte[] {1}, "text/plain");
    Part artifact2 = Part.fromBytes(new byte[] {1, 2}, "text/plain");
    var unused1 =
        service.saveArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact1).blockingGet();
    var unused2 =
        service.saveArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact2).blockingGet();
    Optional<Part> result =
        asOptional(service.loadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, Optional.empty()));
    assertThat(result).hasValue(artifact2);
  }

  @Test
  public void saveAndReloadArtifact_reloadsArtifact() {
    Part artifact = Part.fromBytes(new byte[] {1, 2, 3}, "text/plain");
    Optional<Part> result =
        asOptional(
            service.saveAndReloadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact));
    assertThat(result).hasValue(artifact);
  }

  private static <T> Optional<T> asOptional(Maybe<T> maybe) {
    return maybe.map(Optional::of).defaultIfEmpty(Optional.empty()).blockingGet();
  }

  private static <T> Optional<T> asOptional(Single<T> single) {
    return Optional.of(single.blockingGet());
  }
}
