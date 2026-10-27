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

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createRootAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SaveFilesAsArtifactsPluginTest {

  private static final int MAX_SIZE = 20 * 1024 * 1024;
  private static final String INVOCATION_ID = "test-invocation-id";
  private static final String MIME_TYPE = "application/pdf";
  private static final String PLACEHOLDER = "[Uploaded Artifact: \"%s\"]";
  private static final String UPLOAD_ERROR =
      "[Upload Error: File large.pdf (%s MB) exceeds the maximum supported size of 20MB. Please"
          + " upload a smaller file.]";
  private static final String STORED_URI = "%s%s/%d";
  private static final String GS_PREFIX = "gs://bucket/";
  private static final String APP_NAME = "InMemoryRunner";
  private static final String USER_ID = "user1";

  private final FakeArtifactService service = FakeArtifactService.create();
  private final InvocationContext context = invocationContext(service);
  private final SessionKey sessionKey = context.session().sessionKey();

  @Test
  public void constructor_defaultName() {
    assertThat(new SaveFilesAsArtifactsPlugin().getName())
        .isEqualTo("save_files_as_artifacts_plugin");
  }

  @Test
  public void onUserMessage_savesTheFileAndPutsAPlaceholderAndReferenceInItsPlace() {
    Part file = filePart("report.pdf");

    Optional<Content> result =
        onUserMessage(new SaveFilesAsArtifactsPlugin(), context, userMessage(file));

    assertThat(service.saves).containsExactly(new Save("report.pdf", file));
    assertThat(result)
        .hasValue(
            userMessage(
                placeholder("report.pdf"), reference("report.pdf", "gs://bucket/report.pdf/0")));
  }

  @Test
  public void onUserMessage_attachFileReferenceFalse_putsOnlyAPlaceholderAndReportsTheVersion() {
    SaveFilesAsArtifactsPlugin plugin =
        new SaveFilesAsArtifactsPlugin(/* attachFileReference= */ false);
    int earlierVersion =
        service
            .saveArtifact(sessionKey, "report.pdf", filePart("report.pdf", "old contents"))
            .blockingGet();
    Part file = filePart("report.pdf");

    Optional<Content> result = onUserMessage(plugin, context, userMessage(file));

    assertThat(Iterables.getLast(service.saves)).isEqualTo(new Save("report.pdf", file));
    assertThat(result).hasValue(userMessage(placeholder("report.pdf")));
    assertThat(beforeAgent(plugin, context)).containsExactly("report.pdf", earlierVersion + 1);
  }

  @Test
  public void onUserMessage_uriTheModelCannotRead_putsOnlyAPlaceholder() {
    InvocationContext fileContext =
        invocationContext(FakeArtifactService.withUriPrefix("file:///tmp/"));

    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(), fileContext, userMessage(filePart("report.pdf")));

    assertThat(result).hasValue(userMessage(placeholder("report.pdf")));
  }

  @Test
  public void onUserMessage_uriWithoutScheme_putsOnlyAPlaceholder() {
    InvocationContext noSchemeContext =
        invocationContext(FakeArtifactService.withUriPrefix("bucket/"));

    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(), noSchemeContext, userMessage(filePart("report.pdf")));

    assertThat(result).hasValue(userMessage(placeholder("report.pdf")));
  }

  @Test
  public void onUserMessage_httpsUri_addsTheReference() {
    InvocationContext httpsContext =
        invocationContext(FakeArtifactService.withUriPrefix("https://example.com/"));

    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(), httpsContext, userMessage(filePart("report.pdf")));

    assertThat(result)
        .hasValue(
            userMessage(
                placeholder("report.pdf"),
                reference("report.pdf", "https://example.com/report.pdf/0")));
  }

  @Test
  public void onUserMessage_upperCaseUriScheme_addsTheReference() {
    InvocationContext upperCaseContext =
        invocationContext(FakeArtifactService.withUriPrefix("HTTP://example.com/"));

    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(),
            upperCaseContext,
            userMessage(filePart("report.pdf")));

    assertThat(result)
        .hasValue(
            userMessage(
                placeholder("report.pdf"),
                reference("report.pdf", "HTTP://example.com/report.pdf/0")));
  }

  @Test
  public void onUserMessage_fileWithoutMimeType_takesTheReferenceTypeFromTheStoredFile() {
    Part file =
        Part.builder()
            .inlineData(
                Blob.builder().mimeType("").displayName("notes").data("text".getBytes(UTF_8)))
            .build();

    Optional<Content> result =
        onUserMessage(new SaveFilesAsArtifactsPlugin(), context, userMessage(file));

    Part reference =
        Part.builder()
            .fileData(
                FileData.builder()
                    .mimeType("text/plain")
                    .displayName("notes")
                    .fileUri("gs://bucket/notes/0"))
            .build();
    assertThat(result).hasValue(userMessage(placeholder("notes"), reference));
  }

  @Test
  public void onUserMessage_inMemoryService_putsOnlyAPlaceholder() {
    InMemoryArtifactService inMemory = new InMemoryArtifactService();
    InvocationContext inMemoryContext = invocationContext(inMemory);
    Part file = filePart("report.pdf");

    Optional<Content> result =
        onUserMessage(new SaveFilesAsArtifactsPlugin(), inMemoryContext, userMessage(file));

    assertThat(result).hasValue(userMessage(placeholder("report.pdf")));
    SessionKey inMemoryKey = inMemoryContext.session().sessionKey();
    assertThat(inMemory.loadArtifact(inMemoryKey, "report.pdf").blockingGet()).isEqualTo(file);
  }

  @Test
  public void onUserMessage_noDisplayName_savesUnderAGeneratedName() {
    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(/* attachFileReference= */ false),
            context,
            userMessage(Part.fromText("See:"), filePart("")));

    String generatedName = "artifact_test-invocation-id_1";
    assertThat(service.savedNames()).containsExactly(generatedName);
    assertThat(result).hasValue(userMessage(Part.fromText("See:"), placeholder(generatedName)));
  }

  @Test
  public void onUserMessage_blobWithoutDisplayName_savesUnderAGeneratedName() {
    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(/* attachFileReference= */ false),
            context,
            userMessage(Part.fromText("See:"), Part.fromBytes("text".getBytes(UTF_8), MIME_TYPE)));

    String generatedName = "artifact_test-invocation-id_1";
    assertThat(service.savedNames()).containsExactly(generatedName);
    assertThat(result).hasValue(userMessage(Part.fromText("See:"), placeholder(generatedName)));
  }

  @Test
  public void onUserMessage_severalFiles_keepsTheOtherPartsInPlace() {
    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(),
            context,
            userMessage(
                filePart("a.txt"), Part.fromText("Some text between files"), filePart("b.jpg")));

    assertThat(result)
        .hasValue(
            userMessage(
                placeholder("a.txt"),
                reference("a.txt", "gs://bucket/a.txt/0"),
                Part.fromText("Some text between files"),
                placeholder("b.jpg"),
                reference("b.jpg", "gs://bucket/b.jpg/0")));
  }

  @Test
  public void onUserMessage_sameNameTwice_savesBothAndReportsTheLatestVersion() {
    SaveFilesAsArtifactsPlugin plugin = new SaveFilesAsArtifactsPlugin();

    Optional<Content> result =
        onUserMessage(
            plugin,
            context,
            userMessage(filePart("report.pdf", "first"), filePart("report.pdf", "second")));

    assertThat(result)
        .hasValue(
            userMessage(
                placeholder("report.pdf"),
                reference("report.pdf", "gs://bucket/report.pdf/0"),
                placeholder("report.pdf"),
                reference("report.pdf", "gs://bucket/report.pdf/1")));
    assertThat(beforeAgent(plugin, context)).containsExactly("report.pdf", 1);
  }

  @Test
  public void onUserMessage_noArtifactService_returnsTheMessageUnchanged() {
    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(),
            invocationContext(/* artifactService= */ null),
            userMessage(filePart("report.pdf")));

    assertThat(result).isEmpty();
  }

  @Test
  public void onUserMessage_noFiles_returnsTheMessageUnchanged() {
    Content message = userMessage(Part.fromText("Hello"), Part.fromText("No files here"));

    assertThat(onUserMessage(new SaveFilesAsArtifactsPlugin(), context, message)).isEmpty();
    assertThat(service.saves).isEmpty();
  }

  @Test
  public void onUserMessage_noParts_returnsTheMessageUnchanged() {
    Content message = Content.builder().role("user").build();

    assertThat(onUserMessage(new SaveFilesAsArtifactsPlugin(), context, message)).isEmpty();
    assertThat(service.saves).isEmpty();
  }

  @Test
  public void onUserMessage_savesNothingUntilSubscribed() {
    Maybe<Content> pending =
        new SaveFilesAsArtifactsPlugin()
            .onUserMessageCallback(context, userMessage(filePart("report.pdf")));

    assertThat(service.saves).isEmpty();
    pending.test().assertComplete();
    assertThat(service.savedNames()).containsExactly("report.pdf");
  }

  @Test
  public void onUserMessage_saveFails_keepsTheFile() {
    InvocationContext failingContext =
        invocationContext(FakeArtifactService.failingOn("report.pdf"));

    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(), failingContext, userMessage(filePart("report.pdf")));

    assertThat(result).isEmpty();
  }

  @Test
  public void onUserMessage_saveReturnsAnError_keepsTheFile() {
    InvocationContext erroringContext =
        invocationContext(FakeArtifactService.erroringOn("report.pdf"));

    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(), erroringContext, userMessage(filePart("report.pdf")));

    assertThat(result).isEmpty();
  }

  @Test
  public void onUserMessage_oneOfTwoSavesFails_keepsOnlyTheFileThatFailed() {
    InvocationContext failingContext =
        invocationContext(FakeArtifactService.failingOn("failure.pdf"));
    Part failed = filePart("failure.pdf");

    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(),
            failingContext,
            userMessage(filePart("success.pdf"), failed));

    assertThat(result)
        .hasValue(
            userMessage(
                placeholder("success.pdf"),
                reference("success.pdf", "gs://bucket/success.pdf/0"),
                failed));
  }

  @Test
  public void onUserMessage_versionCannotBeReadBack_keepsThePlaceholderAndReportsNoVersion() {
    SaveFilesAsArtifactsPlugin plugin = new SaveFilesAsArtifactsPlugin();
    InvocationContext unreadable = invocationContext(FakeArtifactService.withUnreadableVersions());

    Optional<Content> result =
        onUserMessage(plugin, unreadable, userMessage(filePart("report.pdf")));

    assertThat(result)
        .hasValue(
            userMessage(
                placeholder("report.pdf"), reference("report.pdf", "gs://bucket/report.pdf/0")));
    assertThat(beforeAgent(plugin, unreadable)).isEmpty();
  }

  @Test
  public void onUserMessage_serviceListsNoVersions_reportsNoVersion() {
    SaveFilesAsArtifactsPlugin plugin = new SaveFilesAsArtifactsPlugin();
    InvocationContext dummy = invocationContext(new DummyArtifactService());

    Optional<Content> result = onUserMessage(plugin, dummy, userMessage(filePart("report.pdf")));

    assertThat(result).hasValue(userMessage(placeholder("report.pdf")));
    assertThat(beforeAgent(plugin, dummy)).isEmpty();
  }

  @Test
  public void onUserMessage_fileAtTheSizeLimit_isSaved() {
    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(/* attachFileReference= */ false),
            context,
            userMessage(filePart("max.pdf", MAX_SIZE)));

    assertThat(service.savedNames()).containsExactly("max.pdf");
    assertThat(result).hasValue(userMessage(placeholder("max.pdf")));
  }

  @Test
  public void onUserMessage_fileOverTheSizeLimit_isReplacedWithAnError() {
    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(),
            context,
            userMessage(filePart("small.pdf"), filePart("large.pdf", MAX_SIZE + 1)));

    assertThat(service.savedNames()).containsExactly("small.pdf");
    assertThat(result)
        .hasValue(
            userMessage(
                placeholder("small.pdf"),
                reference("small.pdf", "gs://bucket/small.pdf/0"),
                Part.fromText(UPLOAD_ERROR.formatted("20.00"))));
  }

  @Test
  public void onUserMessage_fileSizeHalfwayBetweenHundredths_roundsHalfToEven() {
    // 20.125 MB, which Python's `:.2f` formats as 20.12.
    int size = MAX_SIZE + MAX_SIZE / 160;

    Optional<Content> result =
        onUserMessage(
            new SaveFilesAsArtifactsPlugin(), context, userMessage(filePart("large.pdf", size)));

    assertThat(result).hasValue(userMessage(Part.fromText(UPLOAD_ERROR.formatted("20.12"))));
  }

  @Test
  public void beforeAgent_reportsTheSavedVersionsOnce() {
    SaveFilesAsArtifactsPlugin plugin = new SaveFilesAsArtifactsPlugin();
    int earlierVersion = service.saveArtifact(sessionKey, "a.txt", filePart("a.txt")).blockingGet();
    assertThat(onUserMessage(plugin, context, userMessage(filePart("a.txt"), filePart("b.txt"))))
        .isPresent();

    ImmutableMap<String, Integer> first = beforeAgent(plugin, context);
    ImmutableMap<String, Integer> second = beforeAgent(plugin, context);

    assertThat(first).containsExactly("a.txt", earlierVersion + 1, "b.txt", 0);
    assertThat(second).isEmpty();
  }

  @Test
  public void beforeAgent_oneOfTwoSavesFails_reportsOnlyTheSavedVersion() {
    SaveFilesAsArtifactsPlugin plugin = new SaveFilesAsArtifactsPlugin();
    InvocationContext failingContext =
        invocationContext(FakeArtifactService.failingOn("failure.pdf"));
    assertThat(
            onUserMessage(
                plugin,
                failingContext,
                userMessage(filePart("success.pdf"), filePart("failure.pdf"))))
        .isPresent();

    assertThat(beforeAgent(plugin, failingContext)).containsExactly("success.pdf", 0);
  }

  @Test
  public void beforeAgent_twoPluginNames_eachReportsOnlyItsOwnVersions() {
    SaveFilesAsArtifactsPlugin first =
        new SaveFilesAsArtifactsPlugin(/* attachFileReference= */ true, "first_plugin");
    SaveFilesAsArtifactsPlugin second =
        new SaveFilesAsArtifactsPlugin(/* attachFileReference= */ true, "second_plugin");
    assertThat(onUserMessage(first, context, userMessage(filePart("a.txt")))).isPresent();
    assertThat(onUserMessage(second, context, userMessage(filePart("b.txt")))).isPresent();

    assertThat(beforeAgent(first, context)).containsExactly("a.txt", 0);
    assertThat(beforeAgent(second, context)).containsExactly("b.txt", 0);
  }

  @Test
  public void beforeAgent_nothingSaved_reportsNothing() {
    assertThat(beforeAgent(new SaveFilesAsArtifactsPlugin(), context)).isEmpty();
  }

  @Test
  public void beforeAgent_reportsNothingUntilSubscribed() {
    SaveFilesAsArtifactsPlugin plugin = new SaveFilesAsArtifactsPlugin();
    assertThat(onUserMessage(plugin, context, userMessage(filePart("report.pdf")))).isPresent();
    CallbackContext callbackContext = new CallbackContext(context, /* eventActions= */ null);

    Maybe<Content> pending = plugin.beforeAgentCallback(context.agent(), callbackContext);

    assertThat(callbackContext.eventActions().artifactDelta()).isEmpty();
    pending.test().assertComplete().assertNoValues();
    assertThat(callbackContext.eventActions().artifactDelta()).containsExactly("report.pdf", 0);
  }

  @Test
  public void runAsync_modelGetsThePlaceholderAndTheAgentEventReportsTheVersion() {
    TestLlm model =
        createTestLlm(createTextLlmResponse("Got it."), createTextLlmResponse("Got it."));
    InMemoryRunner runner =
        new InMemoryRunner(
            createTestAgentBuilder(model).build(),
            APP_NAME,
            ImmutableList.of(new SaveFilesAsArtifactsPlugin()));
    Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();

    ImmutableList<ImmutableList<Event>> turns =
        ImmutableList.of("first", "second").stream()
            .map(text -> runTurn(runner, session, text))
            .collect(toImmutableList());

    Content expectedUserContent = userMessage(Part.fromText("Read this"), placeholder("notes.txt"));
    assertThat(Iterables.getLast(Iterables.getLast(model.getRequests()).contents()))
        .isEqualTo(expectedUserContent);
    assertThat(
            turns.stream()
                .map(events -> events.get(0).actions().artifactDelta())
                .collect(toImmutableList()))
        .containsExactly(ImmutableMap.of("notes.txt", 0), ImmutableMap.of("notes.txt", 1))
        .inOrder();
    Session stored =
        runner
            .sessionService()
            .getSession(APP_NAME, USER_ID, session.id(), Optional.empty())
            .blockingGet();
    assertThat(stored.immutableEvents().get(0).content()).hasValue(expectedUserContent);
    assertThat(stored.state()).isEmpty();
    Part storedFile =
        runner.artifactService().loadArtifact(stored.sessionKey(), "notes.txt").blockingGet();
    assertThat(new String(storedFile.inlineData().flatMap(Blob::data).orElseThrow(), UTF_8))
        .isEqualTo("second");
  }

  private static ImmutableList<Event> runTurn(InMemoryRunner runner, Session session, String text) {
    Content message = userMessage(Part.fromText("Read this"), filePart("notes.txt", text));
    return ImmutableList.copyOf(
        runner.runAsync(USER_ID, session.id(), message).toList().blockingGet());
  }

  private static InvocationContext invocationContext(
      @Nullable BaseArtifactService artifactService) {
    return createInvocationContext(createRootAgent()).toBuilder()
        .artifactService(artifactService)
        .invocationId(INVOCATION_ID)
        .build();
  }

  /** The rewritten message, or empty when the plugin leaves the message unchanged. */
  private static Optional<Content> onUserMessage(
      SaveFilesAsArtifactsPlugin plugin, InvocationContext invocationContext, Content message) {
    return Optional.ofNullable(
        plugin.onUserMessageCallback(invocationContext, message).blockingGet());
  }

  /** The artifact delta the plugin reports on the first agent's event. */
  private static ImmutableMap<String, Integer> beforeAgent(
      SaveFilesAsArtifactsPlugin plugin, InvocationContext invocationContext) {
    CallbackContext callbackContext =
        new CallbackContext(invocationContext, /* eventActions= */ null);
    plugin
        .beforeAgentCallback(invocationContext.agent(), callbackContext)
        .test()
        .assertComplete()
        .assertNoValues();
    return ImmutableMap.copyOf(callbackContext.eventActions().artifactDelta());
  }

  private static Content userMessage(Part... parts) {
    return Content.builder().role("user").parts(parts).build();
  }

  private static Part filePart(String displayName) {
    return filePart(displayName, "file contents");
  }

  private static Part filePart(String displayName, String text) {
    return filePart(displayName, text.getBytes(UTF_8));
  }

  private static Part filePart(String displayName, int size) {
    return filePart(displayName, new byte[size]);
  }

  private static Part filePart(String displayName, byte[] data) {
    return Part.builder()
        .inlineData(Blob.builder().mimeType(MIME_TYPE).displayName(displayName).data(data))
        .build();
  }

  private static Part placeholder(String fileName) {
    return Part.fromText(PLACEHOLDER.formatted(fileName));
  }

  private static Part reference(String fileName, String uri) {
    return Part.builder()
        .fileData(FileData.builder().mimeType(MIME_TYPE).displayName(fileName).fileUri(uri))
        .build();
  }

  private record Save(String fileName, Part artifact) {}

  /**
   * Stores artifacts in memory and reports each saved version at {@code
   * <uriPrefix><filename>/<version>}. Saving a name in {@code failingNames} throws, saving one in
   * {@code erroringNames} returns a failed {@link Single}, and listing versions throws unless
   * {@code versionsReadable}.
   */
  private static final class FakeArtifactService implements BaseArtifactService {
    private final String uriPrefix;
    private final ImmutableSet<String> failingNames;
    private final ImmutableSet<String> erroringNames;
    private final boolean versionsReadable;
    private final InMemoryArtifactService delegate = new InMemoryArtifactService();
    private final List<Save> saves = new ArrayList<>();

    private FakeArtifactService(
        String uriPrefix,
        ImmutableSet<String> failingNames,
        ImmutableSet<String> erroringNames,
        boolean versionsReadable) {
      this.uriPrefix = uriPrefix;
      this.failingNames = failingNames;
      this.erroringNames = erroringNames;
      this.versionsReadable = versionsReadable;
    }

    static FakeArtifactService create() {
      return new FakeArtifactService(GS_PREFIX, ImmutableSet.of(), ImmutableSet.of(), true);
    }

    static FakeArtifactService withUriPrefix(String uriPrefix) {
      return new FakeArtifactService(uriPrefix, ImmutableSet.of(), ImmutableSet.of(), true);
    }

    static FakeArtifactService failingOn(String fileName) {
      return new FakeArtifactService(GS_PREFIX, ImmutableSet.of(fileName), ImmutableSet.of(), true);
    }

    static FakeArtifactService erroringOn(String fileName) {
      return new FakeArtifactService(GS_PREFIX, ImmutableSet.of(), ImmutableSet.of(fileName), true);
    }

    static FakeArtifactService withUnreadableVersions() {
      return new FakeArtifactService(GS_PREFIX, ImmutableSet.of(), ImmutableSet.of(), false);
    }

    ImmutableList<String> savedNames() {
      return saves.stream().map(Save::fileName).collect(toImmutableList());
    }

    @Override
    public Single<Integer> saveArtifact(
        String appName, String userId, String sessionId, String filename, Part artifact) {
      if (failingNames.contains(filename)) {
        throw new IllegalStateException("Storage error");
      }
      if (erroringNames.contains(filename)) {
        return Single.error(() -> new IllegalStateException("Storage error"));
      }
      saves.add(new Save(filename, artifact));
      return delegate.saveArtifact(appName, userId, sessionId, filename, artifact);
    }

    @Override
    public Single<ImmutableList<Integer>> listVersions(
        String appName, String userId, String sessionId, String filename) {
      if (!versionsReadable) {
        throw new IllegalStateException("Storage error");
      }
      return delegate.listVersions(appName, userId, sessionId, filename);
    }

    @Override
    public Single<Part> saveAndReloadArtifact(
        String appName, String userId, String sessionId, String filename, Part artifact) {
      return saveArtifact(appName, userId, sessionId, filename, artifact)
          .map(version -> storedAt(STORED_URI.formatted(uriPrefix, filename, version)));
    }

    @Override
    public Maybe<Part> loadArtifact(
        String appName,
        String userId,
        String sessionId,
        String filename,
        @Nullable Integer version) {
      return delegate.loadArtifact(appName, userId, sessionId, filename, version);
    }

    @Override
    public Single<ListArtifactsResponse> listArtifactKeys(
        String appName, String userId, String sessionId) {
      return delegate.listArtifactKeys(appName, userId, sessionId);
    }

    @Override
    public Completable deleteArtifact(
        String appName, String userId, String sessionId, String filename) {
      return delegate.deleteArtifact(appName, userId, sessionId, filename);
    }

    private static Part storedAt(String uri) {
      return Part.builder()
          .fileData(FileData.builder().mimeType("text/plain").fileUri(uri))
          .build();
    }
  }

  /** Saves nothing: returns version 0, reloads the artifact as given, and lists no versions. */
  private static final class DummyArtifactService implements BaseArtifactService {
    @Override
    public Single<Integer> saveArtifact(
        String appName, String userId, String sessionId, String filename, Part artifact) {
      return Single.just(0);
    }

    @Override
    public Single<Part> saveAndReloadArtifact(
        String appName, String userId, String sessionId, String filename, Part artifact) {
      return Single.just(artifact);
    }

    @Override
    public Maybe<Part> loadArtifact(
        String appName,
        String userId,
        String sessionId,
        String filename,
        @Nullable Integer version) {
      return Maybe.empty();
    }

    @Override
    public Single<ListArtifactsResponse> listArtifactKeys(
        String appName, String userId, String sessionId) {
      return Single.just(ListArtifactsResponse.builder().filenames(ImmutableList.of()).build());
    }

    @Override
    public Completable deleteArtifact(
        String appName, String userId, String sessionId, String filename) {
      return Completable.complete();
    }

    @Override
    public Single<ImmutableList<Integer>> listVersions(
        String appName, String userId, String sessionId, String filename) {
      return Single.just(ImmutableList.of());
    }
  }
}
