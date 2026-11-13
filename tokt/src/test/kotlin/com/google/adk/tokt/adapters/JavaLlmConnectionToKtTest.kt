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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.tokt.adapters

import com.google.adk.kt.agents.LiveRequestQueue as KtLiveRequestQueue
import com.google.adk.kt.agents.LlmAgent as KtLlmAgent
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LlmRequest as KtLlmRequest
import com.google.adk.kt.models.LlmResponse as KtLlmResponse
import com.google.adk.kt.models.RealtimeInput as KtRealtimeInput
import com.google.adk.kt.runners.InMemoryRunner as KtInMemoryRunner
import com.google.adk.kt.types.AutomaticActivityDetection as KtAutomaticActivityDetection
import com.google.adk.kt.types.Blob as KtBlob
import com.google.adk.kt.types.Content as KtContent
import com.google.adk.kt.types.FunctionCall as KtFunctionCall
import com.google.adk.kt.types.FunctionResponse as KtFunctionResponse
import com.google.adk.kt.types.GenerateContentConfig as KtGenerateContentConfig
import com.google.adk.kt.types.LiveConnectConfig as KtLiveConnectConfig
import com.google.adk.kt.types.Modality as KtModality
import com.google.adk.kt.types.Part as KtPart
import com.google.adk.kt.types.RealtimeInputConfig as KtRealtimeInputConfig
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.models.BaseLlmConnection as JavaBaseLlmConnection
import com.google.adk.models.LlmRequest as JavaLlmRequest
import com.google.adk.models.LlmResponse as JavaLlmResponse
import com.google.adk.tokt.JavaAdkToKt
import com.google.adk.tools.BaseTool as JavaBaseTool
import com.google.adk.tools.ToolContext as JavaToolContext
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.genai.types.Blob as GenaiBlob
import com.google.genai.types.Content as GenaiContent
import com.google.genai.types.FunctionCall as GenaiFunctionCall
import com.google.genai.types.FunctionDeclaration as GenaiFunctionDeclaration
import com.google.genai.types.Modality as GenaiModality
import com.google.genai.types.Part as GenaiPart
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.processors.PublishProcessor
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.jvm.optionals.getOrNull
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Tests [JavaLlmConnectionToKt], the live connection [JavaAdkToKt.asKtModel] opens. */
class JavaLlmConnectionToKtTest {

  @Test
  fun javaLiveConnection_receive_endsEachCollectionAtTurnComplete(): Unit = runBlocking {
    val javaLlm = FakeJavaLiveLlm()
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
    try {
      val java = javaLlm.connections.single()
      // ADK Java's Gemini drops unobserved responses, so the adapter subscribes at connect.
      java.responses.onNext(javaLiveResponse("a", turnComplete = false))
      java.responses.onNext(JavaLlmResponse.builder().content(modelText("a2")).build())
      java.responses.onNext(javaLiveResponse("b", turnComplete = true))
      java.responses.onNext(javaLiveResponse("c", turnComplete = true))

      withTimeout(10.seconds) {
        assertEquals(listOf("a", "a2", "b"), connection.receive().toList().texts())
        assertEquals(listOf("c"), connection.receive().toList().texts())
        // Once the Java stream ends, a collection completes empty: the connection is finished.
        java.responses.onComplete()
        assertEquals(emptyList(), connection.receive().toList())
      }
    } finally {
      withContext(NonCancellable) { connection.closeSession() }
    }
  }

  @Test
  fun javaLiveConnection_rejectsASecondConcurrentCollector(): Unit = runBlocking {
    val javaLlm = FakeJavaLiveLlm()
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
    val java = javaLlm.connections.single()
    // A Java close may fail the stream; the first collector must still end normally.
    java.onClose = { java.responses.onError(IllegalStateException("closed")) }
    val first = launch(start = CoroutineStart.UNDISPATCHED) { connection.receive().collect {} }
    try {
      // Bounded, so a missing guard fails here rather than hanging on the second collection.
      val failure =
        assertFailsWith<IllegalStateException> {
          withTimeout(10.seconds) { connection.receive().collect {} }
        }

      assertContains(failure.message.orEmpty(), "one collector at a time")
    } finally {
      withContext(NonCancellable) { connection.closeSession() }
    }
    withTimeout(10.seconds) { first.join() }
  }

  @Test
  fun javaLiveConnection_closeSession_endsAWaitingCollectorAndClosesJavaOnce(): Unit = runBlocking {
    // The fake's stream never completes, so only closeSession can end the collection.
    val javaLlm = FakeJavaLiveLlm()
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
    val collector = async(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }

    connection.closeSession()
    connection.closeSession()
    // A `use` block calls close() after the caller already closed gracefully.
    connection.close()

    assertEquals(emptyList(), withTimeout(10.seconds) { collector.await() })
    assertEquals(1, javaLlm.connections.single().closes.get())
    assertFalse(javaLlm.connections.single().responses.hasSubscribers(), "should unsubscribe")
  }

  @Test
  fun javaLiveConnection_receiveAfterAnEarlyStop_resumesRatherThanReplays(): Unit = runBlocking {
    val javaLlm = FakeJavaLiveLlm()
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
    try {
      val java = javaLlm.connections.single()
      java.responses.onNext(javaLiveResponse("first", turnComplete = false))
      java.responses.onNext(javaLiveResponse("second", turnComplete = false))
      java.responses.onComplete()

      withTimeout(10.seconds) {
        assertEquals(listOf("first"), connection.receive().take(1).toList().texts())
        assertEquals(listOf("second"), connection.receive().toList().texts())
      }
    } finally {
      withContext(NonCancellable) { connection.closeSession() }
    }
  }

  @Test
  fun javaLiveConnection_javaStreamError_failsTheCollector(): Unit = runBlocking {
    val javaLlm = FakeJavaLiveLlm()
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
    try {
      javaLlm.connections.single().responses.onError(IllegalStateException("socket lost"))

      val failure =
        assertFailsWith<IllegalStateException> {
          withTimeout(10.seconds) { connection.receive().toList() }
        }

      assertEquals("socket lost", failure.message)
    } finally {
      withContext(NonCancellable) { connection.closeSession() }
    }
  }

  @Test
  fun javaLiveConnection_sendsHistoryContentAndMedia(): Unit = runBlocking {
    val javaLlm = FakeJavaLiveLlm()
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
    try {
      val java = javaLlm.connections.single()

      connection.sendHistory(
        listOf(KtContent.fromText("user", "hello"), KtContent.fromText("model", "hi there"))
      )
      connection.sendContent(KtContent.fromText("user", "hi"), partial = true)
      connection.sendContent(KtContent.fromText("user", "next"))
      connection.sendRealtime(KtRealtimeInput.Audio(blobKt("audio/pcm")))
      connection.sendRealtime(KtRealtimeInput.Video(blobKt("image/jpeg")))

      assertEquals(
        listOf(listOf("hello"), listOf("hi there")),
        java.history.single().map { content -> content.parts().get().map { it.text().get() } },
      )
      assertEquals(
        listOf("hi", "next"),
        java.contents.map { it.parts().get().single().text().get() },
      )
      assertEquals(listOf(true, false), java.partials)
      assertEquals(listOf("audio/pcm", "image/jpeg"), java.blobs.map { it.mimeType().get() })
    } finally {
      withContext(NonCancellable) { connection.closeSession() }
    }
  }

  @Test
  fun javaLiveConnection_rejectsWhatAJavaConnectionCannotSend(): Unit = runBlocking {
    val javaLlm = FakeJavaLiveLlm()
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
    try {
      for (signal in
        listOf(
          KtRealtimeInput.ActivityStart,
          KtRealtimeInput.ActivityEnd,
          KtRealtimeInput.AudioStreamEnd,
        )) {
        assertFailsWith<UnsupportedOperationException> { connection.sendRealtime(signal) }
      }
      // Content the LiveConnection contract forbids fails before it reaches Java, in every shape.
      val answer = KtPart(functionResponse = KtFunctionResponse(name = "f", id = "1"))
      val call = KtPart(functionCall = KtFunctionCall(name = "f", id = "1"))
      for ((content, partial) in
        listOf(
          KtContent(role = "user", parts = emptyList()) to false,
          KtContent(role = "user", parts = listOf(call)) to false,
          KtContent(role = "user", parts = listOf(answer, KtPart(text = "x"))) to false,
          KtContent(role = "user", parts = listOf(answer)) to true,
        )) {
        assertFailsWith<IllegalArgumentException> { connection.sendContent(content, partial) }
      }
      val java = javaLlm.connections.single()
      assertTrue(java.contents.isEmpty() && java.blobs.isEmpty(), "nothing should reach Java")
    } finally {
      withContext(NonCancellable) { connection.closeSession() }
    }
  }

  @Test
  fun asKtModelConnect_explicitVadSignal_failsBeforeConnecting(): Unit = runBlocking {
    // ADK Java's live connection turns the voice-activity messages this asks for into errors.
    val javaLlm = FakeJavaLiveLlm()
    val model = JavaAdkToKt.asKtModel(javaLlm)

    val failure =
      assertFailsWith<UnsupportedOperationException> {
        model.connect(
          KtLlmRequest(liveConnectConfig = KtLiveConnectConfig(explicitVadSignal = true))
        )
      }

    assertContains(failure.message.orEmpty(), "explicitVadSignal")
    assertTrue(javaLlm.connectRequests.isEmpty(), "nothing should reach Java")
    // Only true asks for the signals, so false connects.
    model
      .connect(KtLlmRequest(liveConnectConfig = KtLiveConnectConfig(explicitVadSignal = false)))
      .closeSession()
  }

  @Test
  fun asKtModelConnect_manualActivityDetection_failsBeforeConnecting(): Unit = runBlocking {
    // The adapter cannot send ActivityStart or ActivityEnd, which manual detection depends on.
    val javaLlm = FakeJavaLiveLlm()
    val model = JavaAdkToKt.asKtModel(javaLlm)
    val manual =
      KtLiveConnectConfig(
        realtimeInputConfig =
          KtRealtimeInputConfig(
            automaticActivityDetection = KtAutomaticActivityDetection(disabled = true)
          )
      )

    val failure =
      assertFailsWith<UnsupportedOperationException> {
        model.connect(KtLlmRequest(liveConnectConfig = manual))
      }

    assertContains(failure.message.orEmpty(), "automaticActivityDetection.disabled")
    assertTrue(javaLlm.connectRequests.isEmpty(), "nothing should connect")
    // Only disabled = true needs ActivityStart and ActivityEnd, so enabled detection connects.
    val automatic =
      KtLiveConnectConfig(
        realtimeInputConfig =
          KtRealtimeInputConfig(
            automaticActivityDetection = KtAutomaticActivityDetection(disabled = false)
          )
      )
    model.connect(KtLlmRequest(liveConnectConfig = automatic)).closeSession()
    assertEquals(1, javaLlm.connectRequests.size)
  }

  @Test
  fun asKtModelConnect_sendsTheLiveConfigToJava(): Unit = runBlocking {
    val javaLlm = FakeJavaLiveLlm()
    JavaAdkToKt.asKtModel(javaLlm)
      .connect(
        KtLlmRequest(
          config =
            KtGenerateContentConfig(systemInstruction = KtContent.fromText("user", "be brief"))
        )
      )
      .closeSession()

    val live = javaLlm.connectRequests.single().liveConnectConfig()
    assertEquals(
      listOf(GenaiModality(GenaiModality.Known.AUDIO)),
      assertNotNull(live.responseModalities().getOrNull()),
    )
    val instruction = assertNotNull(live.systemInstruction().getOrNull())
    assertEquals("be brief", instruction.parts().get().single().text().get())
    // A modality the request names reaches Java as named, not as the AUDIO default.
    JavaAdkToKt.asKtModel(javaLlm)
      .connect(
        KtLlmRequest(
          liveConnectConfig = KtLiveConnectConfig(responseModalities = listOf(KtModality.TEXT))
        )
      )
      .closeSession()
    assertEquals(
      listOf(GenaiModality(GenaiModality.Known.TEXT)),
      assertNotNull(
        javaLlm.connectRequests.last().liveConnectConfig().responseModalities().getOrNull()
      ),
    )
  }

  @Test
  fun asKtModelConnect_runsTheJavaCallsOnTheGivenDispatcher() {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "tokt-live-dispatcher") }
    try {
      runBlocking {
        val javaLlm = FakeJavaLiveLlm()
        val connection =
          JavaAdkToKt.asKtModel(javaLlm, executor.asCoroutineDispatcher()).connect(KtLlmRequest())
        try {
          connection.sendContent(KtContent.fromText("user", "hi"))
        } finally {
          withContext(NonCancellable) { connection.closeSession() }
        }

        // connect, sendContent and close; a " @coroutine#N" suffix may follow the name.
        assertEquals(3, javaLlm.threads.size)
        assertTrue(
          javaLlm.threads.all { it.startsWith("tokt-live-dispatcher") },
          "Java calls should run on the supplied dispatcher; ran on ${javaLlm.threads}",
        )
      }
    } finally {
      executor.shutdown()
    }
  }

  @Test
  fun ktRunnerRunLive_answersAJavaModelsMidTurnFunctionCall(): Unit = runBlocking {
    // ADK Java's Gemini calls a tool mid-turn; the Kotlin loop must answer it within the turn.
    val javaLlm = FakeJavaLiveLlm()
    val runner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(
            name = "a",
            model = JavaAdkToKt.asKtModel(javaLlm),
            tools = listOf(JavaAdkToKt.asKtTool(JavaEchoTool())),
          ),
        appName = "app",
      )
    val queue = KtLiveRequestQueue()
    val events = async { runner.runLive("u", "s", queue).toList() }
    val answer =
      try {
        queue.sendContent(KtContent.fromText("user", "echo hi"))
        val java = javaLlm.awaitConnection()
        java.awaitContents(1)

        java.responses.onNext(
          JavaLlmResponse.builder()
            .content(modelFunctionCall("java_echo", mapOf("text" to "hi")))
            .partial(false)
            .turnComplete(false)
            .build()
        )
        val sent = java.awaitContents(2)[1].parts().get().single().functionResponse().get()
        java.responses.onNext(javaLiveResponse("done", turnComplete = true))
        sent
      } finally {
        queue.close()
      }
    val emitted = withTimeout(10.seconds) { events.await() }

    assertEquals(Optional.of(mapOf<String, Any>("echoed" to "hi")), answer.response())
    assertTrue(emitted.any { it.functionCalls().isNotEmpty() }, "the call should be an event")
    assertTrue(emitted.any { it.functionResponses().isNotEmpty() }, "so should the answer")
    assertTrue(
      emitted.any { event -> event.content?.parts?.any { it.text == "done" } == true },
      "the turn should finish after the answer",
    )
  }

  @Test
  fun javaLiveConnection_closeSessionFromACancelledCaller_stillClosesJava(): Unit = runBlocking {
    // closeSession typically runs in a finally, so often in a caller that is already cancelled.
    val release = CountDownLatch(1)
    val java = FakeJavaLlmConnection(CopyOnWriteArrayList(), closeGate = release)
    val connection = JavaLlmConnectionToKt(java, Dispatchers.IO)
    try {
      val caller =
        launch(start = CoroutineStart.UNDISPATCHED) {
          cancel()
          connection.closeSession()
        }
      withTimeout(10.seconds) { while (java.closes.get() == 0) delay(10.milliseconds) }

      assertFalse(caller.isCompleted, "closeSession should wait for the Java close")
      release.countDown()
      withTimeout(10.seconds) { caller.join() }
      assertEquals(1, java.closes.get())
    } finally {
      release.countDown()
    }
  }

  @Test
  fun javaLiveConnection_closeThatBlocks_returnsAfterTheCloseTimeoutAndEndsCollectors(): Unit =
    runBlocking {
      // A Java close may block, and closeSession runs where nothing else can bound it.
      val release = CountDownLatch(1)
      val java = FakeJavaLlmConnection(CopyOnWriteArrayList(), closeGate = release)
      val connection = JavaLlmConnectionToKt(java, Dispatchers.IO, closeTimeout = 200.milliseconds)
      try {
        val collector = async(start = CoroutineStart.UNDISPATCHED) { connection.receive().toList() }
        val closing = async { connection.closeSession() }

        // Only the wait is bounded here: closeSession itself cannot be cancelled.
        val closed = withTimeoutOrNull(5.seconds) { closing.await() }

        assertNotNull(closed, "closeSession still blocked on the Java close")
        assertEquals(emptyList(), withTimeout(5.seconds) { collector.await() })
        // The close runs on its own thread, so it may not have started yet.
        withTimeout(5.seconds) { while (java.closes.get() == 0) delay(10.milliseconds) }
        assertEquals(1, java.closes.get())
      } finally {
        release.countDown()
      }
    }

  @Test
  fun javaLiveConnection_closeQueuedPastTheTimeout_stillClosesJava(): Unit = runBlocking {
    // A blocking Java send can hold the dispatcher's only thread past the close timeout.
    val executor = Executors.newSingleThreadExecutor()
    val busy = CountDownLatch(1)
    try {
      executor.execute { busy.await() }
      val java = FakeJavaLlmConnection(CopyOnWriteArrayList())
      val connection =
        JavaLlmConnectionToKt(
          java,
          executor.asCoroutineDispatcher(),
          closeTimeout = 100.milliseconds,
        )

      val closing = async { connection.closeSession() }
      assertNotNull(withTimeoutOrNull(5.seconds) { closing.await() }, "closeSession waited")
      busy.countDown()

      withTimeout(5.seconds) { while (java.closes.get() == 0) delay(10.milliseconds) }
      assertEquals(1, java.closes.get())
    } finally {
      busy.countDown()
      executor.shutdown()
    }
  }

  @Test
  fun javaLiveConnection_closeFailure_isRethrownByCloseSession(): Unit = runBlocking {
    val javaLlm = FakeJavaLiveLlm(closeFailure = IllegalStateException("close failed"))
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())

    val failure = assertFailsWith<IllegalStateException> { connection.closeSession() }

    assertEquals("close failed", failure.message)
  }

  @Test
  fun javaLiveConnection_sendTheJavaSessionNeverCompletes_stillCancels(): Unit = runBlocking {
    // A dead ADK Java session never completes a send, and cancelling the run must still return.
    val neverSent = CompletableFuture<Void?>()
    val javaLlm = FakeJavaLiveLlm(pendingSend = neverSent)
    val connection = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
    try {
      val send =
        launch(Dispatchers.Default) {
          connection.sendContent(KtContent(role = "user", parts = listOf(KtPart(text = "hi"))))
        }
      val sendStarted = javaLlm.awaitConnection().sendStarted
      val started = runInterruptible(Dispatchers.IO) { sendStarted.await(5, TimeUnit.SECONDS) }

      send.cancel()
      val returned = withTimeoutOrNull(5.seconds) { send.join() } != null

      assertTrue(started, "the send should reach the Java connection")
      assertTrue(returned, "cancelling the send should return")
    } finally {
      neverSent.complete(null)
      withContext(NonCancellable) { connection.closeSession() }
    }
  }

  @Test
  fun javaLiveConnection_sendFailingAfterItsCallerGaveUp_reportsNothingToRxJava(): Unit =
    runBlocking {
      // Closing a Java session fails its pending sends, often after their callers were cancelled.
      val errors = CopyOnWriteArrayList<Throwable>()
      RxJavaPlugins.setErrorHandler { errors += it }
      val executor = Executors.newSingleThreadExecutor()
      val pending = CompletableFuture<Void?>()
      try {
        val java = FakeJavaLlmConnection(CopyOnWriteArrayList(), pendingSend = pending)
        val connection = JavaLlmConnectionToKt(java, executor.asCoroutineDispatcher())
        val send = launch {
          connection.sendContent(KtContent(role = "user", parts = listOf(KtPart(text = "hi"))))
        }
        assertTrue(runInterruptible(Dispatchers.IO) { java.sendStarted.await(5, TimeUnit.SECONDS) })

        send.cancel()
        withTimeout(5.seconds) { send.join() }
        pending.completeExceptionally(IllegalStateException("session closed"))
        // The executor's only thread runs this once the failed send has finished.
        runInterruptible(Dispatchers.IO) { executor.submit {}.get() }

        assertEquals(emptyList(), errors.map { it.javaClass.simpleName })
      } finally {
        pending.complete(null)
        RxJavaPlugins.setErrorHandler(null)
        executor.shutdown()
      }
    }

  @Test
  fun javaLiveConnection_sendThatThrowsSynchronously_rethrowsTheFailure(): Unit = runBlocking {
    // A Java send may throw instead of returning a failed Completable.
    val java =
      FakeJavaLlmConnection(CopyOnWriteArrayList(), sendFailure = IllegalStateException("rejected"))
    val connection = JavaLlmConnectionToKt(java, Dispatchers.IO)
    try {
      val failure =
        withTimeout(5.seconds) {
          assertFailsWith<IllegalStateException> {
            connection.sendContent(KtContent(role = "user", parts = listOf(KtPart(text = "hi"))))
          }
        }

      assertEquals("rejected", failure.message)
    } finally {
      withContext(NonCancellable) { connection.closeSession() }
    }
  }

  @Test
  fun javaLiveConnection_sendCancelledBeforeItStarts_neverReachesJava(): Unit = runBlocking {
    val hi = KtContent(role = "user", parts = listOf(KtPart(text = "hi")))
    // A caller cancelled before it sends, on a dispatcher that would run the send at once.
    val idle = FakeJavaLlmConnection(CopyOnWriteArrayList())
    val inline = JavaLlmConnectionToKt(idle, Executor { it.run() }.asCoroutineDispatcher())
    launch(start = CoroutineStart.UNDISPATCHED) {
      cancel()
      inline.sendContent(hi)
    }
    assertEquals(emptyList(), idle.contents)

    // A caller cancelled while its send waits for a busy dispatcher.
    val executor = Executors.newSingleThreadExecutor()
    val busy = CountDownLatch(1)
    try {
      val java = FakeJavaLlmConnection(CopyOnWriteArrayList())
      val connection = JavaLlmConnectionToKt(java, executor.asCoroutineDispatcher())
      executor.execute { busy.await() }
      val queued = launch(start = CoroutineStart.UNDISPATCHED) { connection.sendContent(hi) }
      queued.cancel()
      withTimeout(5.seconds) { queued.join() }
      busy.countDown()
      // The executor's only thread runs this once the queued send's task has run.
      runInterruptible(Dispatchers.IO) { executor.submit {}.get() }

      assertEquals(emptyList(), java.contents)
    } finally {
      busy.countDown()
      executor.shutdown()
    }
  }

  @Test
  fun asKtModelConnect_lateCloseFailure_leavesTheCallerCancelled(): Unit = runBlocking {
    // A failing close is suppressed onto the cancellation rather than replacing it.
    val gate = CountDownLatch(1)
    val javaLlm =
      FakeJavaLiveLlm(connectGate = gate, closeFailure = IllegalStateException("close failed"))

    var failure: CancellationException? = null
    val caller = launch {
      failure =
        assertFailsWith<CancellationException> {
          JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
        }
    }
    try {
      withTimeout(10.seconds) { while (javaLlm.connectRequests.isEmpty()) delay(10.milliseconds) }
      caller.cancel()
    } finally {
      gate.countDown()
    }
    withTimeout(10.seconds) { caller.join() }

    assertContains(assertNotNull(failure).suppressedExceptions.map { it.message }, "close failed")
    assertEquals(1, javaLlm.connections.single().closes.get())
  }

  @Test
  fun asKtModelConnect_adapterFailingAfterTheJavaConnect_closesTheJavaConnection(): Unit =
    runBlocking {
      val javaLlm = FakeJavaLiveLlm(receiveFailure = IllegalStateException("no stream"))

      val failure =
        assertFailsWith<IllegalStateException> {
          JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
        }

      assertEquals("no stream", failure.message)
      assertEquals(1, javaLlm.connections.single().closes.get())
    }

  @Test
  fun asKtModelConnect_adapterAndCloseBothFail_keepsTheAdapterFailure(): Unit = runBlocking {
    val javaLlm =
      FakeJavaLiveLlm(
        receiveFailure = IllegalStateException("no stream"),
        closeFailure = IllegalStateException("close failed"),
      )

    val failure =
      assertFailsWith<IllegalStateException> {
        JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
      }

    assertEquals("no stream", failure.message)
    // Stack-trace recovery (kotlinx debug mode) may rethrow a copy whose cause is the original.
    val suppressed =
      generateSequence<Throwable>(failure) { it.cause }.flatMap { it.suppressed.asSequence() }
    assertContains(suppressed.map { it.message }.toList(), "close failed")
  }

  @Test
  fun asKtModelConnect_callerCancelledDuringTheJavaConnect_closesTheLateConnection(): Unit =
    runBlocking {
      // The Java connect is never interrupted; what it opens after its caller gave up is closed.
      val gate = CountDownLatch(1)
      val javaLlm = FakeJavaLiveLlm(connectGate = gate)
      val caller = launch {
        val unused = JavaAdkToKt.asKtModel(javaLlm).connect(KtLlmRequest())
      }
      try {
        withTimeout(10.seconds) { while (javaLlm.connectRequests.isEmpty()) delay(10.milliseconds) }
        caller.cancel()
      } finally {
        gate.countDown()
      }
      withTimeout(10.seconds) { caller.join() }

      assertEquals(1, javaLlm.connections.single().closes.get())
    }

  /**
   * A Java model whose live connections are [FakeJavaLlmConnection]s. It records each connect
   * request and the thread of every Java call; a [connectGate] holds connect until released.
   */
  private class FakeJavaLiveLlm(
    private val connectGate: CountDownLatch? = null,
    private val closeFailure: Throwable? = null,
    private val receiveFailure: Throwable? = null,
    private val pendingSend: CompletableFuture<Void?>? = null,
  ) : JavaBaseLlm("java-live-model") {
    val connectRequests = CopyOnWriteArrayList<JavaLlmRequest>()
    val connections = CopyOnWriteArrayList<FakeJavaLlmConnection>()
    val threads = CopyOnWriteArrayList<String>()

    override fun generateContent(
      llmRequest: JavaLlmRequest,
      stream: Boolean,
    ): Flowable<JavaLlmResponse> =
      Flowable.error(UnsupportedOperationException("FakeJavaLiveLlm answers live connections only"))

    override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection {
      connectRequests += llmRequest
      threads += Thread.currentThread().name
      connectGate?.await()
      return FakeJavaLlmConnection(
          threads,
          closeFailure = closeFailure,
          receiveFailure = receiveFailure,
          pendingSend = pendingSend,
        )
        .also { connections += it }
    }

    suspend fun awaitConnection(): FakeJavaLlmConnection =
      withTimeout(10.seconds) {
        while (connections.isEmpty()) delay(10.milliseconds)
        connections.single()
      }
  }

  /**
   * A Java live connection on a [PublishProcessor], which drops what it emits before a subscriber,
   * as `GeminiLlmConnection` does. It records what it is sent, and closing it does not end its
   * stream unless [onClose] does.
   */
  private class FakeJavaLlmConnection(
    private val threads: MutableList<String>,
    private val closeFailure: Throwable? = null,
    private val receiveFailure: Throwable? = null,
    private val closeGate: CountDownLatch? = null,
    private val pendingSend: CompletableFuture<Void?>? = null,
    private val sendFailure: Throwable? = null,
  ) : JavaBaseLlmConnection {
    /** Counted down once a send backed by [pendingSend] starts blocking. */
    val sendStarted = CountDownLatch(1)

    val responses: PublishProcessor<JavaLlmResponse> = PublishProcessor.create()
    val history = CopyOnWriteArrayList<List<GenaiContent>>()
    val contents = CopyOnWriteArrayList<GenaiContent>()
    val partials = CopyOnWriteArrayList<Boolean>()
    val blobs = CopyOnWriteArrayList<GenaiBlob>()
    val closes = AtomicInteger()
    @Volatile var onClose: () -> Unit = {}

    override fun sendHistory(history: List<GenaiContent>): Completable = record {
      this.history += history
    }

    override fun sendContent(content: GenaiContent): Completable =
      sendContent(content, partial = false)

    // Models `GeminiLlmConnection`'s fromFuture sends: a pending send blocks its subscriber.
    override fun sendContent(content: GenaiContent, partial: Boolean): Completable =
      when {
        sendFailure != null -> throw sendFailure
        pendingSend == null ->
          record {
            contents += content
            partials += partial
          }
        else ->
          Completable.fromAction {
            sendStarted.countDown()
            pendingSend.get()
          }
      }

    override fun sendRealtime(blob: GenaiBlob): Completable = record { blobs += blob }

    override fun receive(): Flowable<JavaLlmResponse> {
      if (receiveFailure != null) throw receiveFailure
      return responses
    }

    override fun close() {
      threads += Thread.currentThread().name
      closes.incrementAndGet()
      onClose()
      closeGate?.await()
      if (closeFailure != null) throw closeFailure
    }

    override fun close(throwable: Throwable) = close()

    @CanIgnoreReturnValue
    suspend fun awaitContents(count: Int): List<GenaiContent> =
      withTimeout(10.seconds) {
        while (contents.size < count) delay(10.milliseconds)
        contents.toList()
      }

    private fun record(action: () -> Unit): Completable = Completable.fromAction {
      threads += Thread.currentThread().name
      action()
    }
  }

  /** A Java tool that echoes its `text` argument. */
  private class JavaEchoTool : JavaBaseTool("java_echo", "echoes args") {
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(GenaiFunctionDeclaration.builder().name("java_echo").build())

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> = Single.just(mapOf("echoed" to (args["text"] ?: "")))
  }

  private companion object {
    fun modelFunctionCall(name: String, args: Map<String, Any>): GenaiContent =
      GenaiContent.builder()
        .role("model")
        .parts(
          GenaiPart.builder()
            .functionCall(GenaiFunctionCall.builder().name(name).args(args).build())
            .build()
        )
        .build()

    fun modelText(text: String): GenaiContent =
      GenaiContent.builder().role("model").parts(GenaiPart.builder().text(text).build()).build()

    fun blobKt(mimeType: String) = KtBlob(mimeType = mimeType, data = byteArrayOf(1, 2))

    /** A model-text live response in the shape `GeminiLlmConnection` builds: not partial. */
    fun javaLiveResponse(text: String, turnComplete: Boolean): JavaLlmResponse =
      JavaLlmResponse.builder()
        .content(modelText(text))
        .partial(false)
        .turnComplete(turnComplete)
        .build()

    /** The first text part of each response that has one. */
    fun List<KtLlmResponse>.texts(): List<String> = mapNotNull {
      it.content?.parts?.firstOrNull()?.text
    }
  }
}
