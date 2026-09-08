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
public final class ConfirmationPolicyTest {

  @Test
  public void canApprove_allowAllWithUnauthenticatedSender_isTrue() {
    assertThat(ConfirmationPolicy.ALLOW_ALL.canApprove(CallerIdentity.unauthenticated())).isTrue();
  }

  @Test
  public void canApprove_allowAllWithAbsentIdentity_isTrue() {
    assertThat(ConfirmationPolicy.ALLOW_ALL.canApprove(CallerIdentity.absent())).isTrue();
  }

  @Test
  public void canApprove_authenticatedOnlyWithAuthenticatedSender_isTrue() {
    assertThat(
            ConfirmationPolicy.AUTHENTICATED_ONLY.canApprove(
                CallerIdentity.authenticatedAs("operator")))
        .isTrue();
  }

  @Test
  public void canApprove_authenticatedOnlyWithUnauthenticatedSender_isFalse() {
    assertThat(ConfirmationPolicy.AUTHENTICATED_ONLY.canApprove(CallerIdentity.unauthenticated()))
        .isFalse();
  }

  @Test
  public void canApprove_authenticatedOnlyWithAbsentIdentity_isFalse() {
    assertThat(ConfirmationPolicy.AUTHENTICATED_ONLY.canApprove(CallerIdentity.absent())).isFalse();
  }

  @Test
  public void canApprove_rejectUnauthenticatedWithAbsentIdentity_isTrue() {
    assertThat(ConfirmationPolicy.REJECT_UNAUTHENTICATED.canApprove(CallerIdentity.absent()))
        .isTrue();
  }

  @Test
  public void canApprove_rejectUnauthenticatedWithUnauthenticatedSender_isFalse() {
    assertThat(
            ConfirmationPolicy.REJECT_UNAUTHENTICATED.canApprove(CallerIdentity.unauthenticated()))
        .isFalse();
  }

  @Test
  public void canApprove_rejectUnauthenticatedWithAuthenticatedSender_isTrue() {
    assertThat(
            ConfirmationPolicy.REJECT_UNAUTHENTICATED.canApprove(
                CallerIdentity.authenticatedAs("operator")))
        .isTrue();
  }
}
