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
package com.google.adk.plugins;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link SaveFilesAsArtifactsPlugin}.
 *
 * <p>Mirrors {@code GlobalInstructionPluginTest}: the two contexts are mocked, while {@link State},
 * {@link Session} and the artifact service are real, so the {@code temp:} hand-off between {@code
 * onUserMessageCallback} and {@code beforeAgentCallback} is exercised rather than stubbed.
 */
@RunWith(JUnit4.class)
public class SaveFilesAsArtifactsPluginTest {

  private static final String APP_NAME = "test_app";
  private static final String USER_ID = "test_user";
  private static final String SESSION_ID = "test_session";
  private static final String INVOCATION_ID = "e-1234";
  private static final String ROLE = "user";
  private static final String FILE_NAME = "report.pdf";
  private static final String OTHER_FILE_NAME = "chart.png";
  private static final String PLACEHOLDER = "[Uploaded Artifact: \"%s\"]";
  private static final String SAVE_FAILED = "artifact store unavailable";
  private static final String GENERATED_NAME = "artifact_%s_0".formatted(INVOCATION_ID);
  private static final String CUSTOM_NAME = "uploads";
  private static final String KEY_FORMAT = "temp:%s:pending_delta:%s";
  private static final String STASH_KEY =
      KEY_FORMAT.formatted(SaveFilesAsArtifactsPlugin.DEFAULT_NAME, INVOCATION_ID);
  private static final byte[] PAYLOAD = "hello artifact".getBytes(UTF_8);
  private static final Throwable RUN_FAILURE = new IllegalStateException("run failed");

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private InvocationContext mockInvocationContext;
  @Mock private CallbackContext mockCallbackContext;
  @Mock private BaseAgent mockAgent;

  private final State state = new State(new ConcurrentHashMap<>());
  private final Session session = Session.builder(SESSION_ID).state(state).build();
  private final BaseArtifactService artifactService = new InMemoryArtifactService();
  private final EventActions eventActions = EventActions.builder().build();
  private final SaveFilesAsArtifactsPlugin plugin = new SaveFilesAsArtifactsPlugin();

  @Before
  public void setUp() {
    state.clear();
    when(mockInvocationContext.invocationId()).thenReturn(INVOCATION_ID);
    when(mockInvocationContext.appName()).thenReturn(APP_NAME);
    when(mockInvocationContext.userId()).thenReturn(USER_ID);
    when(mockInvocationContext.session()).thenReturn(session);
    when(mockInvocationContext.artifactService()).thenReturn(artifactService);

    when(mockCallbackContext.invocationId()).thenReturn(INVOCATION_ID);
    when(mockCallbackContext.state()).thenReturn(state);
    when(mockCallbackContext.eventActions()).thenReturn(eventActions);
  }

  // --- onUserMessageCallback -------------------------------------------------------------------

  @Test
  public void inlineDataPart_savedAndReplacedWithPlaceholder() {
    Content rewritten = runUserMessage(messageWith(blob(FILE_NAME)));

    assertThat(partTexts(rewritten)).containsExactly(placeholder(FILE_NAME));
    assertThat(storedFileNames()).containsExactly(FILE_NAME);
  }

  @Test
  public void blobWithDisplayName_usesDisplayNameAsArtifactName() {
    runUserMessage(messageWith(blob(FILE_NAME)));

    assertThat(storedFileNames()).containsExactly(FILE_NAME);
    assertThat(storedFileNames()).doesNotContain(GENERATED_NAME);
  }

  @Test
  public void blobWithoutDisplayName_usesGeneratedName() {
    runUserMessage(messageWith(blob(null)));

    assertThat(storedFileNames()).containsExactly(GENERATED_NAME);
  }

  @Test
  public void textOnlyMessage_returnsEmpty() {
    Content message = Content.fromParts(Part.fromText("no files here"));

    plugin
        .onUserMessageCallback(mockInvocationContext, message)
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(storedFileNames()).isEmpty();
    assertThat(state).doesNotContainKey(STASH_KEY);
  }

  /** A message with no parts at all, which {@code parts().orElse(...)} has to absorb. */
  @Test
  public void messageWithNoParts_returnsEmpty() {
    Content message = Content.builder().role("user").build();

    plugin
        .onUserMessageCallback(mockInvocationContext, message)
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(storedFileNames()).isEmpty();
    assertThat(state).doesNotContainKey(STASH_KEY);
  }

  /** A blank display name is not a name — the {@code isEmpty} filter must fall back. */
  @Test
  public void blobWithBlankDisplayName_usesGeneratedName() {
    runUserMessage(messageWith(blob("")));

    assertThat(storedFileNames()).containsExactly(GENERATED_NAME);
  }

  /** The rewrite goes through {@code toBuilder()}, so everything but the parts must survive it. */
  @Test
  public void rewrittenMessage_keepsTheRole() {
    Content message =
        Content.builder().role(ROLE).parts(ImmutableList.of(blobPart(FILE_NAME))).build();

    Content rewritten = runUserMessage(message);

    assertThat(rewritten.role()).hasValue(ROLE);
  }

  @Test
  public void noArtifactService_returnsEmptyAndDoesNotThrow() {
    when(mockInvocationContext.artifactService()).thenReturn(null);

    plugin
        .onUserMessageCallback(mockInvocationContext, messageWith(blob(FILE_NAME)))
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(state).doesNotContainKey(STASH_KEY);
  }

  @Test
  public void mixedParts_preservesOrderAndNonBlobParts() {
    Content message =
        Content.fromParts(Part.fromText("before"), blobPart(FILE_NAME), Part.fromText("after"));

    Content rewritten = runUserMessage(message);

    assertThat(partTexts(rewritten))
        .containsExactly("before", placeholder(FILE_NAME), "after")
        .inOrder();
  }

  // --- the plugin's name -------------------------------------------------------------------------

  @Test
  public void defaultName_matchesUpstream() {
    assertThat(new SaveFilesAsArtifactsPlugin().getName())
        .isEqualTo("save_files_as_artifacts_plugin");
  }

  /** A custom name is not cosmetic: it scopes the state key the two hooks hand off through. */
  @Test
  public void customName_scopesTheStash() {
    SaveFilesAsArtifactsPlugin named = new SaveFilesAsArtifactsPlugin(CUSTOM_NAME);

    named
        .onUserMessageCallback(mockInvocationContext, messageWith(blob(FILE_NAME)))
        .test()
        .assertComplete();

    assertThat(named.getName()).isEqualTo(CUSTOM_NAME);
    assertThat(state).containsKey(customStashKey());
    assertThat(state).doesNotContainKey(STASH_KEY);
  }

  // --- the caller's message is never touched ---------------------------------------------------

  /**
   * The defect this plugin's predecessor fixed ({@code Runner} rewriting the caller's parts list in
   * place, <a href="https://github.com/google/adk-java/issues/1377">#1377</a>) must not reappear
   * here. The hook returns a new {@link Content}; the one it was handed keeps its blob.
   */
  @Test
  public void callerMessage_isNotMutated() {
    Content message = Content.fromParts(Part.fromText("before"), blobPart(FILE_NAME));

    Content rewritten = runUserMessage(message);

    assertThat(partTexts(rewritten)).containsExactly("before", placeholder(FILE_NAME)).inOrder();
    assertThat(partTexts(message)).containsExactly("before", "<inline>").inOrder();
    assertThat(message.parts().orElseThrow().get(1).inlineData()).isPresent();
  }

  /**
   * {@code Content.Builder.parts(List)} stores the caller's list <em>without copying</em>, so an
   * immutable list travels straight into the hook. This is the construction that threw in #1377;
   * the plugin copies before rewriting, so it must not care.
   */
  @Test
  public void messageBuiltFromImmutableList_isSavedAndReplaced() {
    Content message =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.fromText("before"), blobPart(FILE_NAME)))
            .build();

    Content rewritten = runUserMessage(message);

    assertThat(partTexts(rewritten)).containsExactly("before", placeholder(FILE_NAME)).inOrder();
    assertThat(partTexts(message)).containsExactly("before", "<inline>").inOrder();
    assertThat(storedFileNames()).containsExactly(FILE_NAME);
  }

  /**
   * {@code Content.Builder.parts(Part.Builder...)} collects to a Guava {@code ImmutableList} inside
   * genai — a second immutable backing the caller cannot influence.
   */
  @Test
  public void messageBuiltFromPartBuilders_isSavedAndReplaced() {
    Content message =
        Content.builder()
            .role("user")
            .parts(Part.fromText("before").toBuilder(), blobPart(FILE_NAME).toBuilder())
            .build();

    Content rewritten = runUserMessage(message);

    assertThat(partTexts(rewritten)).containsExactly("before", placeholder(FILE_NAME)).inOrder();
    assertThat(storedFileNames()).containsExactly(FILE_NAME);
  }

  @Test
  public void multipleBlobs_allSavedAndOrderPreserved() {
    Content message =
        Content.fromParts(blobPart(FILE_NAME), Part.fromText("between"), blobPart(OTHER_FILE_NAME));

    Content rewritten = runUserMessage(message);

    assertThat(partTexts(rewritten))
        .containsExactly(placeholder(FILE_NAME), "between", placeholder(OTHER_FILE_NAME))
        .inOrder();
    assertThat(storedFileNames()).containsExactly(FILE_NAME, OTHER_FILE_NAME);
  }

  @Test
  public void multipleBlobs_allReportedInArtifactDelta() {
    runUserMessage(Content.fromParts(blobPart(FILE_NAME), blobPart(OTHER_FILE_NAME)));

    plugin.beforeAgentCallback(mockAgent, mockCallbackContext).test().assertComplete();

    assertThat(eventActions.artifactDelta()).containsExactly(FILE_NAME, 0, OTHER_FILE_NAME, 0);
  }

  @Test
  public void partialFailure_placeholdersTheSavedPartAndKeepsTheFailedOne() {
    BaseArtifactService failing = failingOnSecondSaveArtifactService();
    when(mockInvocationContext.artifactService()).thenReturn(failing);

    Content rewritten =
        runUserMessage(Content.fromParts(blobPart(FILE_NAME), blobPart(OTHER_FILE_NAME)));

    assertThat(partTexts(rewritten)).containsExactly(placeholder(FILE_NAME), "<inline>").inOrder();
    assertThat(pendingStash()).containsExactly(FILE_NAME, 0);
  }

  @Test
  public void duplicateDisplayNames_reportTheLatestVersion() {
    Content rewritten = runUserMessage(Content.fromParts(blobPart(FILE_NAME), blobPart(FILE_NAME)));

    assertThat(partTexts(rewritten))
        .containsExactly(placeholder(FILE_NAME), placeholder(FILE_NAME));
    assertThat(pendingStash()).containsExactly(FILE_NAME, 1);
  }

  @Test
  public void saveFailure_keepsOriginalPartAndDoesNotFailInvocation() {
    BaseArtifactService failing = failingArtifactService();
    when(mockInvocationContext.artifactService()).thenReturn(failing);

    plugin
        .onUserMessageCallback(mockInvocationContext, messageWith(blob(FILE_NAME)))
        .test()
        .assertNoErrors()
        .assertNoValues()
        .assertComplete();

    assertThat(state).doesNotContainKey(STASH_KEY);
  }

  @Test
  public void payloadRoundTrips() {
    runUserMessage(messageWith(blob(FILE_NAME)));

    Part loaded =
        artifactService.loadArtifact(APP_NAME, USER_ID, SESSION_ID, FILE_NAME, 0).blockingGet();

    assertThat(loaded.inlineData().get().data().get()).isEqualTo(PAYLOAD);
  }

  // --- beforeAgentCallback ---------------------------------------------------------------------

  @Test
  public void savedVersions_appearInArtifactDelta() {
    runUserMessage(messageWith(blob(FILE_NAME)));

    plugin
        .beforeAgentCallback(mockAgent, mockCallbackContext)
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(eventActions.artifactDelta()).containsExactly(FILE_NAME, 0);
  }

  /**
   * The drain writes entry by entry into the live map, so a delta another plugin already reported
   * on the same event survives. Clearing or replacing it would silently drop their artifact.
   */
  @Test
  public void existingArtifactDelta_isPreserved() {
    eventActions.artifactDelta().put(OTHER_FILE_NAME, 7);
    runUserMessage(messageWith(blob(FILE_NAME)));

    plugin.beforeAgentCallback(mockAgent, mockCallbackContext).test().assertComplete();

    assertThat(eventActions.artifactDelta()).containsExactly(OTHER_FILE_NAME, 7, FILE_NAME, 0);
  }

  @Test
  public void secondAgentCallback_seesEmptyDelta() {
    runUserMessage(messageWith(blob(FILE_NAME)));
    plugin.beforeAgentCallback(mockAgent, mockCallbackContext).test().assertComplete();
    eventActions.artifactDelta().clear();

    plugin.beforeAgentCallback(mockAgent, mockCallbackContext).test().assertComplete();

    assertThat(eventActions.artifactDelta()).isEmpty();
  }

  // --- afterRunCallback: the halt-before-agent cleanup ------------------------------------------

  @Test
  public void afterRunCallback_clearsStashWhenAgentNeverRan() {
    runUserMessage(messageWith(blob(FILE_NAME)));
    assertThat(pendingStash()).isNotEmpty();

    plugin.afterRunCallback(mockInvocationContext).test().assertComplete();

    assertThat(pendingStash()).isEmpty();
  }

  @Test
  public void afterRunCallback_withNothingStashed_addsNoStateEntry() {
    plugin.afterRunCallback(mockInvocationContext).test().assertComplete();

    assertThat(state).doesNotContainKey(STASH_KEY);
  }

  @Test
  public void afterRunCallback_afterDrain_isANoOp() {
    runUserMessage(messageWith(blob(FILE_NAME)));
    plugin.beforeAgentCallback(mockAgent, mockCallbackContext).test().assertComplete();

    plugin.afterRunCallback(mockInvocationContext).test().assertComplete();

    assertThat(eventActions.artifactDelta()).containsExactly(FILE_NAME, 0);
    assertThat(pendingStash()).isEmpty();
  }

  // --- onRunErrorCallback: the same cleanup, on the failing run ---------------------------------

  @Test
  public void onRunErrorCallback_clearsStashWhenRunFailed() {
    runUserMessage(messageWith(blob(FILE_NAME)));
    assertThat(pendingStash()).isNotEmpty();

    plugin.onRunErrorCallback(mockInvocationContext, RUN_FAILURE).test().assertComplete();

    assertThat(pendingStash()).isEmpty();
  }

  @Test
  public void onRunErrorCallback_withNothingStashed_addsNoStateEntry() {
    plugin.onRunErrorCallback(mockInvocationContext, RUN_FAILURE).test().assertComplete();

    assertThat(state).doesNotContainKey(STASH_KEY);
  }

  /** Both hooks can fire for one run, so clearing twice must be indistinguishable from once. */
  @Test
  public void onRunErrorCallback_thenAfterRunCallback_isANoOp() {
    runUserMessage(messageWith(blob(FILE_NAME)));

    plugin.onRunErrorCallback(mockInvocationContext, RUN_FAILURE).test().assertComplete();
    plugin.afterRunCallback(mockInvocationContext).test().assertComplete();

    assertThat(pendingStash()).isEmpty();
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** Runs the hook and returns the rewritten message, failing the test if none was produced. */
  private Content runUserMessage(Content message) {
    return plugin
        .onUserMessageCallback(mockInvocationContext, message)
        .test()
        .assertNoErrors()
        .assertComplete()
        .values()
        .get(0);
  }

  private static String placeholder(String fileName) {
    return PLACEHOLDER.formatted(fileName);
  }

  private static String customStashKey() {
    return KEY_FORMAT.formatted(CUSTOM_NAME, INVOCATION_ID);
  }

  private static Content messageWith(Blob inlineData) {
    return Content.fromParts(Part.builder().inlineData(inlineData).build());
  }

  private static Part blobPart(String displayName) {
    return Part.builder().inlineData(blob(displayName)).build();
  }

  private static Blob blob(String displayName) {
    Blob.Builder builder = Blob.builder().mimeType("application/pdf").data(PAYLOAD);
    return displayName == null ? builder.build() : builder.displayName(displayName).build();
  }

  private static ImmutableList<String> partTexts(Content content) {
    return content.parts().orElseThrow().stream()
        .map(part -> part.text().orElse("<inline>"))
        .collect(toImmutableList());
  }

  private ImmutableList<String> storedFileNames() {
    return artifactService
        .listArtifactKeys(APP_NAME, USER_ID, SESSION_ID)
        .blockingGet()
        .filenames();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Integer> pendingStash() {
    Object stashed = state.get(STASH_KEY);
    return stashed instanceof Map ? (Map<String, Integer>) stashed : Map.of();
  }

  /**
   * Fails every save. The error is carried by the returned {@link Single} rather than thrown from
   * the method body: a synchronous throw escapes the Rx chain and never reaches the plugin's {@code
   * onErrorReturn}, which would test nothing.
   */
  private static BaseArtifactService failingArtifactService() {
    BaseArtifactService failing = mock(BaseArtifactService.class);
    when(failing.saveArtifact(anyString(), anyString(), anyString(), anyString(), any(Part.class)))
        .thenReturn(Single.error(() -> new IllegalStateException(SAVE_FAILED)));
    return failing;
  }

  /**
   * Saves the first artifact and fails every save after it, via consecutive stubbing rather than an
   * answer with a counter, so the test carries no mutable state of its own.
   */
  private static BaseArtifactService failingOnSecondSaveArtifactService() {
    BaseArtifactService failing = mock(BaseArtifactService.class);
    when(failing.saveArtifact(anyString(), anyString(), anyString(), anyString(), any(Part.class)))
        .thenReturn(Single.just(0))
        .thenReturn(Single.error(() -> new IllegalStateException(SAVE_FAILED)));
    return failing;
  }
}
