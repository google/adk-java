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

import com.google.genai.types.FunctionCall;

/**
 * Decides whether the sender of a request may satisfy a human tool confirmation.
 *
 * <p>A confirmation stands in for a human operator's consent, but a request arriving over a
 * machine-to-machine transport is recorded under the same {@code user} role as one a human typed,
 * so the flow cannot tell them apart on its own. The default is {@link #REJECT_UNAUTHENTICATED};
 * {@link #ALLOW_ALL} restores the historical behavior.
 */
@FunctionalInterface
public interface ConfirmationApprover {

  /** Accepts every sender, including an unauthenticated peer. */
  ConfirmationApprover ALLOW_ALL = (caller, call) -> true;

  /**
   * Rejects a sender the transport saw and did not authenticate, and accepts one it never saw.
   *
   * <p>The default. A transport that reports an unauthenticated sender is one anybody can reach, so
   * its "approval" is worth nothing; a surface that carries no identity at all is unchanged, which
   * keeps in-process and local runs working.
   */
  ConfirmationApprover REJECT_UNAUTHENTICATED =
      (caller, call) -> !caller.transportKnowsSender() || caller.authenticated();

  /** Accepts only a sender the transport authenticated, rejecting one it never saw. */
  ConfirmationApprover AUTHENTICATED_ONLY = (caller, call) -> caller.authenticated();

  /**
   * Returns whether {@code caller} may approve {@code originalCall}.
   *
   * @param caller the sender of the request carrying the confirmation; an unauthenticated sender is
   *     {@link CallerIdentity#unauthenticated()}, never null
   * @param originalCall the tool call awaiting confirmation, so a policy can decide per tool
   */
  boolean canApprove(CallerIdentity caller, FunctionCall originalCall);
}
