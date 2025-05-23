package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ToolContext}. */
@RunWith(JUnit4.class)
public final class ToolContextTest {

  private InvocationContext mockInvocationContext;
  private BaseArtifactService mockArtifactService;
  private Session testSession;
  private LlmAgent mockAgent;

  @Before
  public void setUp() {
    mockInvocationContext = mock(InvocationContext.class);
    mockArtifactService = mock(BaseArtifactService.class);
    mockAgent = mock(LlmAgent.class);
    // Create a real Session object instead of mocking it.
    testSession = Session.builder("testSession").appName("testApp").userId("testUser").build();

    when(mockInvocationContext.artifactService()).thenReturn(mockArtifactService);
    // Return the real Session object when session() is called on the mock InvocationContext.
    when(mockInvocationContext.session()).thenReturn(testSession);
    when(mockInvocationContext.agent()).thenReturn(mockAgent);
  }

  @Test
  public void listArtifacts_artifactServiceAvailable_returnsFilenames() {
    ListArtifactsResponse mockResponse =
        ListArtifactsResponse.builder()
            .filenames(ImmutableList.of("file1.txt", "file2.jpg"))
            .build();
    when(mockArtifactService.listArtifactKeys(anyString(), anyString(), anyString()))
        .thenReturn(Single.just(mockResponse));

    ToolContext toolContext = ToolContext.builder(mockInvocationContext).build();
    List<String> filenames = toolContext.listArtifacts().blockingGet();

    assertThat(filenames).containsExactly("file1.txt", "file2.jpg");
  }

  @Test
  public void listArtifacts_artifactServiceNotAvailable_throwsException() {
    when(mockInvocationContext.artifactService()).thenReturn(null);

    ToolContext toolContext = ToolContext.builder(mockInvocationContext).build();

    IllegalStateException exception =
        assertThrows(IllegalStateException.class, toolContext::listArtifacts);
    assertThat(exception).hasMessageThat().isEqualTo("Artifact service is not initialized.");
  }

  @Test
  public void listArtifacts_noArtifacts_returnsEmptyList() {
    ListArtifactsResponse mockResponse =
        ListArtifactsResponse.builder().filenames(ImmutableList.of()).build();
    when(mockArtifactService.listArtifactKeys(anyString(), anyString(), anyString()))
        .thenReturn(Single.just(mockResponse));

    ToolContext toolContext = ToolContext.builder(mockInvocationContext).build();
    List<String> filenames = toolContext.listArtifacts().blockingGet();

    assertThat(filenames).isEmpty();
  }
}
