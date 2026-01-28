package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import com.google.protobuf.ByteString;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class SaveFilesAsArtifactsPluginTest {

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  private static final String APP_NAME = "test_app";
  private static final String USER_ID = "test_user";
  private static final String SESSION_ID = "test_session";
  private static final String INVOCATION_ID = "test_invocation";

  @Mock private BaseArtifactService mockArtifactService;
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

    when(mockArtifactService.saveArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(partWithInlineData)))
        .thenReturn(Single.just(1));
    // Load artifact returns part without FileData
    when(mockArtifactService.loadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(Optional.of(1))))
        .thenReturn(Maybe.just(Part.fromText("a part without file data")));

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get()).hasSize(1);
    assertThat(result.parts().get().get(0).text())
        .hasValue("[Uploaded Artifact: \"" + fileName + "\"]");
  }

  @Test
  public void
      onUserMessageCallback_withInlineDataAndSuccessfulSaveAndAccessibleUri_returnsTextAndUriParts() {
    Part partWithInlineData = createInlineDataPart("text/plain", "hello");
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();
    String fileName = "artifact_" + INVOCATION_ID + "_0";
    String fileUri = "gs://my-bucket/artifact_test_invocation_0";
    String mimeType = "text/plain";

    when(mockArtifactService.saveArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(partWithInlineData)))
        .thenReturn(Single.just(1));
    when(mockArtifactService.loadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(Optional.of(1))))
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

    assertThat(result.parts().get()).hasSize(2);
    assertThat(result.parts().get().get(0).text().get())
        .isEqualTo("[Uploaded Artifact: \"" + fileName + "\"]");
    assertThat(result.parts().get().get(1).fileData().get().fileUri().get()).isEqualTo(fileUri);
    assertThat(result.parts().get().get(1).fileData().get().mimeType().get()).isEqualTo(mimeType);
    assertThat(result.parts().get().get(1).fileData().get().displayName()).hasValue(fileName);
  }

  @Test
  public void
      onUserMessageCallback_withInlineDataAndSuccessfulSaveAndInaccessibleUri_returnsTextPart() {
    Part partWithInlineData = createInlineDataPart("text/plain", "hello");
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();
    String fileName = "artifact_" + INVOCATION_ID + "_0";
    String fileUri = "file://my-bucket/artifact_test_invocation_0"; // Inaccessible scheme
    String mimeType = "text/plain";

    when(mockArtifactService.saveArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(partWithInlineData)))
        .thenReturn(Single.just(1));
    when(mockArtifactService.loadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(Optional.of(1))))
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

    assertThat(result.parts().get()).hasSize(1);
    assertThat(result.parts().get().get(0).text())
        .hasValue("[Uploaded Artifact: \"" + fileName + "\"]");
  }

  @Test
  public void onUserMessageCallback_withInlineDataAndFailedSave_returnsOriginalPart() {
    Part partWithInlineData = createInlineDataPart("text/plain", "hello");
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();
    String fileName = "artifact_" + INVOCATION_ID + "_0";

    when(mockArtifactService.saveArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName), eq(partWithInlineData)))
        .thenReturn(Single.error(new RuntimeException("Failed to save")));

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

    when(mockArtifactService.saveArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName1), eq(partWithInlineData1)))
        .thenReturn(Single.just(1));
    when(mockArtifactService.saveArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName2), eq(partWithInlineData2)))
        .thenReturn(Single.just(2));

    when(mockArtifactService.loadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName1), eq(Optional.of(1))))
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
    when(mockArtifactService.loadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(fileName2), eq(Optional.of(2))))
        .thenReturn(Maybe.empty());

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get()).hasSize(4);
    assertThat(result.parts().get().get(0)).isEqualTo(textPart);
    assertThat(result.parts().get().get(1).text())
        .hasValue("[Uploaded Artifact: \"" + fileName1 + "\"]");
    assertThat(result.parts().get().get(2).fileData().get().fileUri()).hasValue(fileUri1);
    assertThat(result.parts().get().get(3).text())
        .hasValue("[Uploaded Artifact: \"" + fileName2 + "\"]");
  }

  @Test
  public void onUserMessageCallback_withDisplayName_usesDisplayNameAsFileName() {
    String displayName = "mydocument.txt";
    Part partWithInlineData = createInlineDataPart("text/plain", "hello", Optional.of(displayName));
    Content userMessage = Content.builder().parts(partWithInlineData).role("user").build();

    when(mockArtifactService.saveArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(displayName), eq(partWithInlineData)))
        .thenReturn(Single.just(1));
    when(mockArtifactService.loadArtifact(
            eq(APP_NAME), eq(USER_ID), eq(SESSION_ID), eq(displayName), eq(Optional.of(1))))
        .thenReturn(Maybe.empty());

    Content result = plugin.onUserMessageCallback(invocationContext, userMessage).blockingGet();

    assertThat(result.parts().get()).hasSize(1);
    assertThat(result.parts().get().get(0).text())
        .hasValue("[Uploaded Artifact: \"" + displayName + "\"]");
  }
}
