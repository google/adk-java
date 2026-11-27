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

package com.google.adk.runner;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.events.Event;
import com.google.adk.sessions.SessionKey;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class InMemorySteeringServiceTest {
  private final InMemorySteeringService service = new InMemorySteeringService();
  private final SessionKey key = new SessionKey("app", "user", "session");
  private final Content message =
      Content.builder().role("user").parts(Part.fromText("input")).build();

  @Test
  public void snapshotsRetainUnacknowledgedInputsAndReceiptsDoNotResubmit() {
    BaseSteeringService.Inbox inbox = service.open(key, "invocation");
    Single<Event> first = service.submit(key, "invocation", message);
    Single<Event> second = service.submit(key, "invocation", message);
    List<BaseSteeringService.Input> batch = inbox.pending();
    assertThat(batch).hasSize(2);
    assertThat(inbox.pending()).containsExactlyElementsIn(batch).inOrder();
    assertThat(inbox.closeIfIdle()).isFalse();
    TestObserver<Event> disposed = first.test();
    disposed.dispose();
    TestObserver<Event> receipt = first.test().assertNotComplete();
    assertThat(inbox.pending()).hasSize(2);
    Event event = event(batch.get(0));
    inbox.persisted(event);
    inbox.persisted(event);
    receipt.assertResult(event);
    first.test().assertResult(event);
    assertThat(inbox.pending()).containsExactly(batch.get(1));
    assertThat(inbox.closeIfIdle()).isFalse();
    Event last = event(batch.get(1));
    inbox.persisted(last);
    second.test().assertResult(last);
    assertThat(inbox.closeIfIdle()).isTrue();
    assertThrows(IllegalStateException.class, () -> service.submit(key, "invocation", message));
    inbox.close();
  }

  @Test
  public void closureRejectsAdmissionAndFailsReceiptsWithoutLateResurrection() {
    BaseSteeringService.Inbox inbox = service.open(key, "invocation");
    Single<Event> receipt = service.submit(key, "invocation", message);
    Event event = event(inbox.pending().get(0));
    inbox.close();
    inbox.close();
    inbox.persisted(event);
    receipt.test().assertError(IllegalStateException.class);
    assertThat(inbox.pending()).isEmpty();
    assertThat(inbox.closeIfIdle()).isTrue();
    assertThrows(IllegalStateException.class, () -> service.submit(key, "invocation", message));
  }

  @Test
  public void fullIdentityIsRequiredAndDuplicateOpenDoesNotReplaceOwner() {
    BaseSteeringService.Inbox owner = service.open(key, "invocation");
    assertThrows(IllegalStateException.class, () -> service.open(key, "invocation"));
    for (SessionKey other :
        ImmutableList.of(
            new SessionKey("other", "user", "session"),
            new SessionKey("app", "other", "session"),
            new SessionKey("app", "user", "other"))) {
      assertThrows(IllegalStateException.class, () -> service.submit(other, "invocation", message));
    }
    assertThrows(IllegalStateException.class, () -> service.submit(key, "other", message));
    Single<Event> receipt = service.submit(key, "invocation", message);
    assertThat(owner.pending()).hasSize(1);
    owner.persisted(event(owner.pending().get(0)));
    receipt.test().assertComplete().assertNoErrors();
    owner.close();
  }

  @Test
  public void receiptCallbackCanSubmitBeforeNormalClosure() {
    BaseSteeringService.Inbox inbox = service.open(key, "invocation");
    Single<Event> first = service.submit(key, "invocation", message);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      TestObserver<Event> observed =
          first
              .doOnSuccess(event ->
                  executor
                      .submit(() -> service.submit(key, "invocation", message))
                      .get(5, TimeUnit.SECONDS))
              .test();
      inbox.persisted(event(inbox.pending().get(0)));
      observed.assertComplete().assertNoErrors();
      assertThat(inbox.closeIfIdle()).isFalse();
      assertThat(inbox.pending()).hasSize(1);
    } finally {
      inbox.close();
      executor.shutdownNow();
    }
  }

  @Test
  public void uuidGenerationAllowsConcurrentClosure() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicReference<BaseSteeringService.Inbox> opened = new AtomicReference<>();
    InMemorySteeringService service =
        new InMemorySteeringService(() -> {
          try {
            executor.submit(opened.get()::close).get(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
          } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("UUID generation must not block inbox closure", e);
          }
          return "input";
        });
    BaseSteeringService.Inbox inbox = service.open(key, "invocation");
    opened.set(inbox);
    try {
      assertThrows(IllegalStateException.class, () -> service.submit(key, "invocation", message));
      assertThat(inbox.pending()).isEmpty();
    } finally {
      inbox.close();
      executor.shutdownNow();
    }
  }

  private static Event event(BaseSteeringService.Input input) {
    return Event.builder()
        .id(input.id())
        .invocationId("invocation")
        .author("user")
        .content(input.content())
        .build();
  }
}
