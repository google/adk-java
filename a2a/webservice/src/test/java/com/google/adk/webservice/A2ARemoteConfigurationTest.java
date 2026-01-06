package com.google.adk.webservice;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = {A2ARemoteConfiguration.class, TestAgentConfig.class})
public class A2ARemoteConfigurationTest {

  @Autowired private A2ASendMessageExecutor executor;
  @Autowired private BaseSessionService sessionService;
  @Autowired private BaseArtifactService artifactService;
  @Autowired private BaseMemoryService memoryService;

  @Test
  public void beanCreation_withoutCustomServices_usesDefaults() {
    assertNotNull(executor);
    assertTrue(sessionService instanceof InMemorySessionService);
    assertTrue(artifactService instanceof InMemoryArtifactService);
    assertTrue(memoryService instanceof InMemoryMemoryService);
  }
}
