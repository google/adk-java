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

/**
 * Policy deciding whether a request sender may approve a tool confirmation.
 *
 * <p>Defaults to {@link #REJECT_UNAUTHENTICATED}. Use {@link #ALLOW_ALL} to allow all callers.
 */
public enum ConfirmationPolicy {

  /** Allows any caller to approve tool confirmations, including unauthenticated callers. */
  ALLOW_ALL {
    @Override
    public boolean canApprove(CallerIdentity caller) {
      return true;
    }
  },

  /** Allows only authenticated callers to approve tool confirmations. */
  AUTHENTICATED_ONLY {
    @Override
    public boolean canApprove(CallerIdentity caller) {
      return caller.authenticated();
    }
  },

  /**
   * Rejects unauthenticated transport callers while allowing authenticated callers and local runs
   * with {@link CallerIdentity#absent()}.
   */
  REJECT_UNAUTHENTICATED {
    @Override
    public boolean canApprove(CallerIdentity caller) {
      return !caller.transportKnowsSender() || caller.authenticated();
    }
  };

  /** Returns true if {@code caller} is allowed to approve a pending tool confirmation. */
  public abstract boolean canApprove(CallerIdentity caller);
}
