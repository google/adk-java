package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import com.google.protobuf.ByteString;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link SaveFilesAsArtifactsPlugin}.
 *
 * <p>This class tests the following scenarios:
 *
 * <ul>
 *   <li>The plugin returns the original message if no artifact service is available.
 *   <li>The plugin returns no message if the user message contains no inline data.
 *   <li>The plugin correctly saves artifacts when inline data is present, and:
 *       <ul>
 *         <li>Returns a text part indicating upload if the saved artifact has no URI.
 *         <li>Returns a text part and a file data part if the saved artifact has an accessible URI.
 *         <li>Returns only a text part if the saved artifact has an inaccessible URI.
 *       </ul>
 *   <li>The plugin returns the original part if saving the artifact fails.
 *   <li>The plugin correctly handles messages with multiple parts, some with inline data and some
 *       without.
 *   <li>The plugin uses the display name from the inline data as the artifact file name if
 *       provided.
 * </ul>
 */
@RunWith(JUnit4.class)
public class SaveFilesAsArtifactsPluginTest {

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  private static final String APP_NAME = "test_app";
  private static final String USER_ID = "test_user";
  private static final String SESSION_ID = "test_session";
  private static final String INVOCATION_ID = "test_invocation";

  @Mock private BaseArtifactService mockArtifactService;
  @Mock private CallbackContext mockCallbackContext;
  @Mock private BaseTool mockTool;
  private Session session;

  private SaveFilesAsArtifactsPlugin plugin;
  private InvocationContext invocationContext;
  private InvocationContext invocationContextWithNoArtifactService;

  @Before
  public void setUp() {
    session = Session.builder(SESSION_ID).appName(APP_NAME).userId(USER_ID).build();

    invocationContext =
        InvocationContext.builder()
            .invocationId(INVOCATION_ID)
            .session(session)
            .artifactService(mockArtifactService)
            .build();
    invocationContextWithNoArtifactService =
        InvocationContext.builder()
            .invocationId(INVOCATION_ID)
            .session(session)
            .artifactService(null)
            .build();
    plugin = new SaveFilesAsArtifactsPlugin();
  }

  private Part createInlineDataPart(String mimeType, String data) {
    return createInlineDataPart(mimeType, data, Optional.empty());
  }

  private Part createInlineDataPart(String mimeType, String data, Optional<String> displayName) {
    Blob.Builder blobBuilder =
        Blob.builder().mimeType(mimeType).data(ByteString.copyFromUtf8(data).toByteArray());
    displayName.ifPresent(blobBuilder::displayName);
    return Part.builder().inlineData(blobBuilder.build()).build();
  }

  @Test
  public void getName_withDefaultConstructor_returnsDefaultName() {
    SaveFilesAsArtifactsPlugin defaultPlugin = new SaveFilesAsArtifactsPlugin();
    assertThat(defaultPlugin.getName()).isEqualTo("save_files_as_artifacts_plugin");
  }

  @Test
  public void getName_withNameConstructor_returnsName() {
    SaveFilesAsArtifactsPlugin namedPlugin = new SaveFilesAsArtifactsPlugin("custom_name");
    assertThat(namedPlugin.getName()).isEqualTo("custom_name");
  }

  @Test
  public void onUserMessageCallback_noArtifactService_returnsMessage() {
    Part partWithInlineData = createInlineDataPart("text/plain", "hello");
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();

    plugin
        .onUserMessageCallback(invocationContextWithNoArtifactService, userMessage)
        .test()
        .assertValue(userMessage);
  }

  @Test
  public void onUserMessageCallback_noInlineData_returnsEmpty() {
    Content userMessage = Content.builder().parts(Part.fromText("hello")).role("user").build();
    plugin.onUserMessageCallback(invocationContext, userMessage).test().assertNoValues();
  }

  @Test
  public void onUserMessageCallback_withInlineDataAndSuccessfulSaveAndNoUri_returnsTextPart() {
    Part partWithInlineData = createInlineDataPart("text/plain", "hello");
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();
    String fileName = "artifact_" + INVOCATION_ID + "_0";

    // Load artifact returns part without FileData
    when(mockArtifactService.saveAndReloadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(partWithInlineData)))
        .thenReturn(Maybe.just(Part.fromText("a part without file data")));

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get())
        .containsExactly(Part.fromText("[Uploaded Artifact: \"" + fileName + "\"]"));
  }

  @Test
  public void
      onUserMessageCallback_withInlineDataAndSuccessfulSaveAndAccessibleUri_returnsTextAndUriParts() {
    Part partWithInlineData = createInlineDataPart("text/plain", "hello");
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();
    String fileName = "artifact_" + INVOCATION_ID + "_0";
    String fileUri = "gs://my-bucket/artifact_test_invocation_0";
    String mimeType = "text/plain";

    when(mockArtifactService.saveAndReloadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(partWithInlineData)))
        .thenReturn(
            Maybe.just(
                Part.builder()
                    .fileData(
                        FileData.builder()
                            .fileUri(fileUri)
                            .mimeType(mimeType)
                            .displayName(fileName)
                            .build())
                    .build()));

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get())
        .containsExactly(
            Part.fromText("[Uploaded Artifact: \"" + fileName + "\"]"),
            Part.builder()
                .fileData(
                    FileData.builder()
                        .fileUri(fileUri)
                        .mimeType(mimeType)
                        .displayName(fileName)
                        .build())
                .build())
        .inOrder();
  }

  @Test
  public void
      onUserMessageCallback_withInlineDataAndSuccessfulSaveAndInaccessibleUri_returnsTextPart() {
    Part partWithInlineData = createInlineDataPart("text/plain", "hello");
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();
    String fileName = "artifact_" + INVOCATION_ID + "_0";
    String fileUri = "file://my-bucket/artifact_test_invocation_0"; // Inaccessible scheme
    String mimeType = "text/plain";

    when(mockArtifactService.saveAndReloadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(partWithInlineData)))
        .thenReturn(
            Maybe.just(
                Part.builder()
                    .fileData(
                        FileData.builder()
                            .fileUri(fileUri)
                            .mimeType(mimeType)
                            .displayName(fileName)
                            .build())
                    .build()));

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get())
        .containsExactly(Part.fromText("[Uploaded Artifact: \"" + fileName + "\"]"));
  }

  @Test
  public void onUserMessageCallback_withInlineDataAndFailedSave_returnsOriginalPart() {
    Part partWithInlineData = createInlineDataPart("text/plain", "hello");
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();
    String fileName = "artifact_" + INVOCATION_ID + "_0";

    when(mockArtifactService.saveAndReloadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(partWithInlineData)))
        .thenReturn(Maybe.error(new RuntimeException("Failed to save")));

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get()).containsExactly(partWithInlineData);
  }

  @Test
  public void onUserMessageCallback_withInlineDataAndMultipleParts_returnsMixedParts() {
    Part textPart = Part.fromText("this is text");
    Part partWithInlineData1 = createInlineDataPart("text/plain", "inline1");
    Part partWithInlineData2 = createInlineDataPart("image/png", "inline2");
    Content userMessage =
        Content.builder()
            .parts(ImmutableList.of(textPart, partWithInlineData1, partWithInlineData2))
            .role("user")
            .build();

    String fileName1 = "artifact_" + INVOCATION_ID + "_0";
    String fileName2 = "artifact_" + INVOCATION_ID + "_1";
    String fileUri1 = "gs://my-bucket/artifact_test_invocation_0";
    String mimeType1 = "text/plain";

    when(mockArtifactService.saveAndReloadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName1), eq(partWithInlineData1)))
        .thenReturn(
            Maybe.just(
                Part.builder()
                    .fileData(
                        FileData.builder()
                            .fileUri(fileUri1)
                            .mimeType(mimeType1)
                            .displayName(fileName1)
                            .build())
                    .build()));
    // For 2nd artifact, do not return a file URI.
    when(mockArtifactService.saveAndReloadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName2), eq(partWithInlineData2)))
        .thenReturn(Maybe.empty());

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get())
        .containsExactly(
            textPart,
            Part.fromText("[Uploaded Artifact: \"" + fileName1 + "\"]"),
            Part.builder()
                .fileData(
                    FileData.builder()
                        .fileUri(fileUri1)
                        .mimeType(mimeType1)
                        .displayName(fileName1)
                        .build())
                .build(),
            Part.fromText("[Uploaded Artifact: \"" + fileName2 + "\"]"))
        .inOrder();
  }

  @Test
  public void onUserMessageCallback_withDisplayName_usesDisplayNameAsFileName() {
    String displayName = "mydocument.txt";
    Part partWithInlineData = createInlineDataPart("text/plain", "hello", Optional.of(displayName));
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();

    when(mockArtifactService.saveAndReloadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(displayName), eq(partWithInlineData)))
        .thenReturn(Maybe.empty());

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get())
        .containsExactly(Part.fromText("[Uploaded Artifact: \"" + displayName + "\"]"));
  }

  @Test
  public void beforeRunCallback_returnsEmpty() {
    plugin.beforeRunCallback(invocationContext).test().assertNoValues();
  }

  @Test
  public void afterRunCallback_returnsComplete() {
    plugin.afterRunCallback(invocationContext).test().assertComplete();
  }

  @Test
  public void beforeAgentCallback_returnsEmpty() {
    plugin.beforeAgentCallback(null, mockCallbackContext).test().assertNoValues();
  }

  @Test
  public void afterAgentCallback_returnsEmpty() {
    plugin.afterAgentCallback(null, mockCallbackContext).test().assertNoValues();
  }

  @Test
  public void beforeModelCallback_returnsEmpty() {
    plugin.beforeModelCallback(mockCallbackContext, null).test().assertNoValues();
  }

  @Test
  public void afterModelCallback_returnsEmpty() {
    plugin.afterModelCallback(mockCallbackContext, null).test().assertNoValues();
  }

  @Test
  public void beforeToolCallback_returnsEmpty() {
    plugin.beforeToolCallback(mockTool, null, null).test().assertNoValues();
  }

  @Test
  public void afterToolCallback_returnsEmpty() {
    plugin.afterToolCallback(mockTool, null, null, null).test().assertNoValues();
  }

  @Test
  public void onModelErrorCallback_returnsEmpty() {
    plugin.onModelErrorCallback(mockCallbackContext, null, null).test().assertNoValues();
  }

  @Test
  public void onToolErrorCallback_returnsEmpty() {
    plugin.onToolErrorCallback(mockTool, null, null, null).test().assertNoValues();
  }

  @Test
  public void onEventCallback_returnsEmpty() {
    plugin.onEventCallback(invocationContext, null).test().assertNoValues();
  }
}
