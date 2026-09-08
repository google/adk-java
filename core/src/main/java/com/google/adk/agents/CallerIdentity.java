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
 * What the transport knows about the sender of the current request.
 *
 * <p>Three states: {@link #absent()} where no transport identity reached this invocation, {@link
 * #unauthenticated()} where a transport saw the sender and did not authenticate it, and {@link
 * #authenticatedAs(String)} where it did. A {@link ConfirmationApprover} decides which of them may
 * satisfy a human tool confirmation.
 */
@AutoValue
public abstract class CallerIdentity {

  /**
   * Whether the transport told us anything about the sender at all.
   *
   * <p>False means no transport identity reached this invocation, which is the ordinary shape for
   * an in-process run or a surface that does not carry one -- not a claim that the sender is
   * anonymous.
   */
  public abstract boolean transportKnowsSender();

  /** Whether the transport authenticated the sender. */
  public abstract boolean authenticated();

  /** The authenticated name, present only when {@link #authenticated()} is true. */
  public abstract Optional<String> name();

  /** No transport identity reached this invocation. */
  public static CallerIdentity absent() {
    return new AutoValue_CallerIdentity(false, false, Optional.empty());
  }

  /** A sender the transport saw but did not authenticate. */
  public static CallerIdentity unauthenticated() {
    return new AutoValue_CallerIdentity(true, false, Optional.empty());
  }

  /** A sender the transport authenticated as {@code name}. */
  public static CallerIdentity authenticatedAs(String name) {
    return new AutoValue_CallerIdentity(true, true, Optional.of(name));
  }

  /**
   * Maps a transport's own view of the sender onto this type.
   *
   * <p>Fails closed: a name is kept only alongside {@code authenticated}, so an unauthenticated
   * name cannot be mistaken for evidence, and an authenticated sender whose transport supplies no
   * usable name is reported as unauthenticated rather than as an anonymous authenticated one.
   */
  public static CallerIdentity of(boolean authenticated, @Nullable String name) {
    return authenticated && name != null && !name.isEmpty()
        ? authenticatedAs(name)
        : unauthenticated();
  }
}
