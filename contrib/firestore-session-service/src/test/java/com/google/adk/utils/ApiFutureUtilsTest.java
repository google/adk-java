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

package com.google.adk.utils;

import com.google.api.core.SettableApiFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Test class for ApiFutureUtils. */
public class ApiFutureUtilsTest {

  @BeforeEach
  public void setup() {
    // Override the default executor to run on the same thread for tests.
    // This prevents noisy async stack traces from being logged during failure tests.
    ApiFutureUtils.executor = MoreExecutors.directExecutor();
  }

  /** Tests that ApiFutureUtils.toSingle emits the expected result on success. */
  @Test
  void toSingle_onSuccess_emitsResult() {
    SettableApiFuture<String> future = SettableApiFuture.create();
    Single<String> single = ApiFutureUtils.toSingle(future);
    TestObserver<String> testObserver = single.test();

    future.set("success");

    testObserver.awaitCount(1);
    testObserver.assertNoErrors();
    testObserver.assertValue("success");
  }

  /** Tests that ApiFutureUtils.toSingle emits the expected error on failure. */
  @Test
  void toSingle_onFailure_emitsError() {
    SettableApiFuture<String> future = SettableApiFuture.create();
    Single<String> single = ApiFutureUtils.toSingle(future);
    TestObserver<String> testObserver = single.test();
    Exception testException = new RuntimeException("test-exception");

    future.setException(testException);

    testObserver.awaitCount(1);
    testObserver.assertError(testException);
    testObserver.assertNotComplete();
  }

  /** Tests that ApiFutureUtils.toMaybe emits the expected result on success. */
  @Test
  void toMaybe_onSuccess_emitsResult() {
    SettableApiFuture<String> future = SettableApiFuture.create();
    Maybe<String> maybe = ApiFutureUtils.toMaybe(future);
    TestObserver<String> testObserver = maybe.test();

    future.set("success");

    testObserver.awaitCount(1);
    testObserver.assertNoErrors();
    testObserver.assertValue("success");
  }

  /** Tests that ApiFutureUtils.toMaybe emits the expected error on failure. */
  @Test
  void toMaybe_onFailure_emitsError() {
    SettableApiFuture<String> future = SettableApiFuture.create();
    Maybe<String> maybe = ApiFutureUtils.toMaybe(future);
    TestObserver<String> testObserver = maybe.test();
    Exception testException = new RuntimeException("test-exception");

    future.setException(testException);

    testObserver.awaitCount(1);
    testObserver.assertError(testException);
    testObserver.assertNotComplete();
  }
}
