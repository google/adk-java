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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CallerIdentityTest {

  @Test
  public void absent_knowsNothingAboutSender() {
    CallerIdentity identity = CallerIdentity.absent();

    assertThat(identity.transportKnowsSender()).isFalse();
    assertThat(identity.authenticated()).isFalse();
    assertThat(identity.name()).isEmpty();
  }

  @Test
  public void unauthenticated_sawSenderButDidNotAuthenticateIt() {
    CallerIdentity identity = CallerIdentity.unauthenticated();

    assertThat(identity.transportKnowsSender()).isTrue();
    assertThat(identity.authenticated()).isFalse();
    assertThat(identity.name()).isEmpty();
  }

  @Test
  public void authenticatedAs_carriesTheAuthenticatedName() {
    CallerIdentity identity = CallerIdentity.authenticatedAs("operator");

    assertThat(identity.transportKnowsSender()).isTrue();
    assertThat(identity.authenticated()).isTrue();
    assertThat(identity.name()).hasValue("operator");
  }

  @Test
  public void of_authenticatedWithName_keepsTheName() {
    assertThat(CallerIdentity.of(true, "operator"))
        .isEqualTo(CallerIdentity.authenticatedAs("operator"));
  }

  @Test
  public void of_unauthenticatedWithName_discardsTheName() {
    CallerIdentity identity = CallerIdentity.of(false, "spoofed");

    assertThat(identity.authenticated()).isFalse();
    assertThat(identity.name()).isEmpty();
    assertThat(identity).isEqualTo(CallerIdentity.unauthenticated());
  }

  @Test
  public void of_authenticatedWithNullName_failsClosedToUnauthenticated() {
    assertThat(CallerIdentity.of(true, null)).isEqualTo(CallerIdentity.unauthenticated());
  }

  @Test
  public void of_authenticatedWithEmptyName_failsClosedToUnauthenticated() {
    assertThat(CallerIdentity.of(true, "")).isEqualTo(CallerIdentity.unauthenticated());
  }
}
