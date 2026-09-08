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

import com.google.auto.value.AutoValue;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Represents what the transport knows about the request sender's identity.
 *
 * <p>Can be {@link #absent()} when no transport identity is provided, {@link #unauthenticated()}
 * when the transport did not authenticate the sender, or {@link #authenticatedAs(String)} when it
 * did.
 */
@AutoValue
public abstract class CallerIdentity {

  /** Returns true if the transport reported a sender state (authenticated or unauthenticated). */
  public abstract boolean transportKnowsSender();

  /** Returns true if the transport authenticated the sender. */
  public abstract boolean authenticated();

  /** Returns the authenticated sender name, present only when {@link #authenticated()} is true. */
  public abstract Optional<String> name();

  /** Returns an identity indicating that no transport identity was provided. */
  public static CallerIdentity absent() {
    return new AutoValue_CallerIdentity(false, false, Optional.empty());
  }

  /** Returns an identity for a sender that the transport did not authenticate. */
  public static CallerIdentity unauthenticated() {
    return new AutoValue_CallerIdentity(true, false, Optional.empty());
  }

  /** Returns an identity for a sender authenticated as {@code name}. */
  public static CallerIdentity authenticatedAs(String name) {
    return new AutoValue_CallerIdentity(true, true, Optional.of(name));
  }

  /**
   * Creates a {@link CallerIdentity} from transport authentication state. Returns {@link
   * #unauthenticated()} if {@code authenticated} is false or {@code name} is null or empty.
   */
  public static CallerIdentity of(boolean authenticated, @Nullable String name) {
    return authenticated && name != null && !name.isEmpty()
        ? authenticatedAs(name)
        : unauthenticated();
  }
}
