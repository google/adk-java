package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class PluginManagerTest {

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  private PluginManager pluginManager = new PluginManager();
  @Mock private BasePlugin plugin1;
  @Mock private BasePlugin plugin2;
  @Mock private InvocationContext mockInvocationContext;
  private Content content = Content.builder().build();
  private Session session = Session.builder("session_id").build();

  @Before
  public void setUp() {
    when(plugin1.getName()).thenReturn("plugin1");
    when(plugin2.getName()).thenReturn("plugin2");
    when(mockInvocationContext.session()).thenReturn(session);
  }

  @Test
  public void registerPlugin_success() {
    pluginManager.registerPlugin(plugin1);
    assertThat(pluginManager.getPlugin("plugin1")).isPresent();
  }

  @Test
  public void registerPlugin_duplicateName_throwsException() {
    pluginManager.registerPlugin(plugin1);
    assertThrows(IllegalArgumentException.class, () -> pluginManager.registerPlugin(plugin1));
  }

  @Test
  public void getPlugin_notFound() {
    assertThat(pluginManager.getPlugin("nonexistent")).isEmpty();
  }

  @Test
  public void runOnUserMessageCallback_noPlugins() {
    pluginManager.runOnUserMessageCallback(mockInvocationContext, content).test().assertResult();
  }

  @Test
  public void runOnUserMessageCallback_allReturnEmpty() {
    when(plugin1.onUserMessageCallback(any(), any())).thenReturn(Maybe.empty());
    when(plugin2.onUserMessageCallback(any(), any())).thenReturn(Maybe.empty());
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager.runOnUserMessageCallback(mockInvocationContext, content).test().assertResult();

    verify(plugin1).onUserMessageCallback(mockInvocationContext, content);
    verify(plugin2).onUserMessageCallback(mockInvocationContext, content);
  }

  @Test
  public void runOnUserMessageCallback_plugin1ReturnsValue_earlyExit() {
    Content expectedContent = Content.builder().build();
    when(plugin1.onUserMessageCallback(any(), any())).thenReturn(Maybe.just(expectedContent));
    when(plugin2.onUserMessageCallback(any(), any())).thenReturn(Maybe.empty());
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager
        .runOnUserMessageCallback(mockInvocationContext, content)
        .test()
        .assertResult(expectedContent);

    verify(plugin1).onUserMessageCallback(mockInvocationContext, content);
    verify(plugin2, never()).onUserMessageCallback(any(), any());
  }

  @Test
  public void runOnUserMessageCallback_pluginOrderRespected() {
    Content expectedContent = Content.builder().build();
    when(plugin1.onUserMessageCallback(any(), any())).thenReturn(Maybe.empty());
    when(plugin2.onUserMessageCallback(any(), any())).thenReturn(Maybe.just(expectedContent));
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager
        .runOnUserMessageCallback(mockInvocationContext, content)
        .test()
        .assertResult(expectedContent);

    InOrder inOrder = inOrder(plugin1, plugin2);
    inOrder.verify(plugin1).onUserMessageCallback(mockInvocationContext, content);
    inOrder.verify(plugin2).onUserMessageCallback(mockInvocationContext, content);
  }

  @Test
  public void runAfterRunCallback_allComplete() {
    when(plugin1.afterRunCallback(any())).thenReturn(Completable.complete());
    when(plugin2.afterRunCallback(any())).thenReturn(Completable.complete());
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager.runAfterRunCallback(mockInvocationContext).test().assertResult();

    verify(plugin1).afterRunCallback(mockInvocationContext);
    verify(plugin2).afterRunCallback(mockInvocationContext);
  }

  @Test
  public void runAfterRunCallback_plugin1Fails() {
    RuntimeException testException = new RuntimeException("Test");
    when(plugin1.afterRunCallback(any())).thenReturn(Completable.error(testException));
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager.runAfterRunCallback(mockInvocationContext).test().assertError(testException);

    verify(plugin1).afterRunCallback(mockInvocationContext);
    verify(plugin2, never()).afterRunCallback(any());
  }

  // Example test for another callback type (runBeforeAgentCallback)
  @Test
  public void runBeforeAgentCallback_plugin2ReturnsValue() {
    BaseAgent mockAgent = mock(BaseAgent.class);
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    Content expectedContent = Content.builder().build();

    when(plugin1.beforeAgentCallback(any(), any())).thenReturn(Maybe.empty());
    when(plugin2.beforeAgentCallback(any(), any())).thenReturn(Maybe.just(expectedContent));
    pluginManager.registerPlugin(plugin1);
    pluginManager.registerPlugin(plugin2);

    pluginManager
        .runBeforeAgentCallback(mockAgent, mockCallbackContext)
        .test()
        .assertResult(expectedContent);

    verify(plugin1).beforeAgentCallback(mockAgent, mockCallbackContext);
    verify(plugin2).beforeAgentCallback(mockAgent, mockCallbackContext);
  }
}
