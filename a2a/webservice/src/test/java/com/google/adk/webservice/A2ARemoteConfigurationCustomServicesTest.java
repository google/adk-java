package com.google.adk.webservice;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import com.google.adk.a2a.A2ASendMessageExecutor;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@ContextConfiguration(
    classes = {
      A2ARemoteConfiguration.class,
      TestAgentConfig.class,
      A2ARemoteConfigurationCustomServicesTest.WithCustomServicesTestConfig.class
    })
public class A2ARemoteConfigurationCustomServicesTest {

  @TestConfiguration
  static class WithCustomServicesTestConfig {
    private static final BaseSessionService CUSTOM_SESSION_SERVICE = new InMemorySessionService();
    private static final BaseArtifactService CUSTOM_ARTIFACT_SERVICE =
        new InMemoryArtifactService();
    private static final BaseMemoryService CUSTOM_MEMORY_SERVICE = new InMemoryMemoryService();

    @Bean
    public BaseSessionService customSessionService() {
      return CUSTOM_SESSION_SERVICE;
    }

    @Bean
    public BaseArtifactService customArtifactService() {
      return CUSTOM_ARTIFACT_SERVICE;
    }

    @Bean
    public BaseMemoryService customMemoryService() {
      return CUSTOM_MEMORY_SERVICE;
    }
  }

  @Autowired private A2ASendMessageExecutor executor;
  @Autowired private BaseSessionService sessionService;
  @Autowired private BaseArtifactService artifactService;
  @Autowired private BaseMemoryService memoryService;

  @Test
  public void beanCreation_withCustomServices_autowires() {
    assertNotNull(executor);
    assertSame(WithCustomServicesTestConfig.CUSTOM_SESSION_SERVICE, sessionService);
    assertSame(WithCustomServicesTestConfig.CUSTOM_ARTIFACT_SERVICE, artifactService);
    assertSame(WithCustomServicesTestConfig.CUSTOM_MEMORY_SERVICE, memoryService);
  }
}
