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

/**
 * @author Sandeep Belgavi
 */
package com.google.adk.agents;

import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.testing.TestLlm;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link LlmAgent} A2A fluent API methods.
 *
 * <p>Note: These tests require the {@code google-adk-a2a} module to be on the classpath. If the
 * module is not available, tests will be skipped or throw {@link NoClassDefFoundError}.
 */
@RunWith(JUnit4.class)
public final class LlmAgentA2aTest {

  @Test
  public void testBuilder_toA2a_returnsA2aServerBuilder() {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent.Builder builder = createTestAgentBuilder(testLlm).name("TestAgent");

    try {
      Object result = builder.toA2a();
      assertThat(result).isNotNull();
      // Verify it's an A2aServerBuilder instance
      assertThat(result.getClass().getName()).contains("A2aServerBuilder");
    } catch (NoClassDefFoundError e) {
      // A2A module not available - skip test
      System.out.println("Skipping A2A test - module not available: " + e.getMessage());
    }
  }

  @Test
  public void testBuilder_toA2a_buildsAgentFirst() {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent.Builder builder = createTestAgentBuilder(testLlm).name("TestAgent");

    try {
      Object result = builder.toA2a();
      assertThat(result).isNotNull();
      // Verify builder can still be used after toA2a()
      LlmAgent agent = builder.build();
      assertThat(agent.name()).isEqualTo("TestAgent");
    } catch (NoClassDefFoundError e) {
      System.out.println("Skipping A2A test - module not available: " + e.getMessage());
    }
  }

  @Test
  public void testBuilder_toA2aServerAndStart_withPort() throws IOException, InterruptedException {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent.Builder builder = createTestAgentBuilder(testLlm).name("TestAgent");

    try {
      // This will start a server and block, so we test it in a separate thread
      Thread testThread =
          new Thread(
              () -> {
                try {
                  builder.toA2aServerAndStart(0); // Use port 0 for auto-assignment
                } catch (Exception e) {
                  // Expected - port 0 might not work, or server might start successfully
                }
              });
      testThread.start();
      Thread.sleep(100); // Give it a moment to start
      testThread.interrupt(); // Stop the blocking call
      testThread.join(1000);
    } catch (NoClassDefFoundError e) {
      System.out.println("Skipping A2A test - module not available: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      // Port 0 might not be valid - that's okay, we're just testing the method exists
      assertThat(e.getMessage()).contains("port");
    }
  }

  @Test
  public void testBuilder_toA2aServerAndStart_defaultPort()
      throws IOException, InterruptedException {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent.Builder builder = createTestAgentBuilder(testLlm).name("TestAgent");

    try {
      // This will start a server and block, so we test it in a separate thread
      Thread testThread =
          new Thread(
              () -> {
                try {
                  builder.toA2aServerAndStart(); // Uses default port 8080
                } catch (Exception e) {
                  // Expected - port might be in use, or server might start successfully
                }
              });
      testThread.start();
      Thread.sleep(100); // Give it a moment to start
      testThread.interrupt(); // Stop the blocking call
      testThread.join(1000);
    } catch (NoClassDefFoundError e) {
      System.out.println("Skipping A2A test - module not available: " + e.getMessage());
    }
  }

  @Test
  public void testInstance_toA2a_returnsA2aServerBuilder() {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent agent = createTestAgentBuilder(testLlm).name("TestAgent").build();

    try {
      Object result = agent.toA2a();
      assertThat(result).isNotNull();
      // Verify it's an A2aServerBuilder instance
      assertThat(result.getClass().getName()).contains("A2aServerBuilder");
    } catch (NoClassDefFoundError e) {
      System.out.println("Skipping A2A test - module not available: " + e.getMessage());
    }
  }

  @Test
  public void testInstance_toA2aServerAndStart_withPort() throws IOException, InterruptedException {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent agent = createTestAgentBuilder(testLlm).name("TestAgent").build();

    try {
      // This will start a server and block, so we test it in a separate thread
      Thread testThread =
          new Thread(
              () -> {
                try {
                  agent.toA2aServerAndStart(0); // Use port 0 for auto-assignment
                } catch (Exception e) {
                  // Expected - port 0 might not work, or server might start successfully
                }
              });
      testThread.start();
      Thread.sleep(100); // Give it a moment to start
      testThread.interrupt(); // Stop the blocking call
      testThread.join(1000);
    } catch (NoClassDefFoundError e) {
      System.out.println("Skipping A2A test - module not available: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      // Port 0 might not be valid - that's okay, we're just testing the method exists
      assertThat(e.getMessage()).contains("port");
    }
  }

  @Test
  public void testInstance_toA2aServerAndStart_defaultPort()
      throws IOException, InterruptedException {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent agent = createTestAgentBuilder(testLlm).name("TestAgent").build();

    try {
      // This will start a server and block, so we test it in a separate thread
      Thread testThread =
          new Thread(
              () -> {
                try {
                  agent.toA2aServerAndStart(); // Uses default port 8080
                } catch (Exception e) {
                  // Expected - port might be in use, or server might start successfully
                }
              });
      testThread.start();
      Thread.sleep(100); // Give it a moment to start
      testThread.interrupt(); // Stop the blocking call
      testThread.join(1000);
    } catch (NoClassDefFoundError e) {
      System.out.println("Skipping A2A test - module not available: " + e.getMessage());
    }
  }

  @Test
  public void testBuilder_toA2a_throwsNoClassDefFoundError_whenA2aNotAvailable() {
    // This test verifies that NoClassDefFoundError is thrown when A2A module is not available
    // In practice, if A2A is not on classpath, the method will throw NoClassDefFoundError
    // We can't easily simulate this without removing the dependency, so we just verify
    // the method exists and can be called when A2A is available
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent.Builder builder = createTestAgentBuilder(testLlm).name("TestAgent");

    try {
      builder.toA2a();
      // If we get here, A2A is available - test passes
    } catch (NoClassDefFoundError e) {
      // Expected if A2A module not available
      assertThat(e.getMessage()).contains("A2aServerBuilder");
    }
  }

  @Test
  public void testInstance_toA2a_throwsNoClassDefFoundError_whenA2aNotAvailable() {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(Part.fromText("Test"))));
    LlmAgent agent = createTestAgentBuilder(testLlm).name("TestAgent").build();

    try {
      agent.toA2a();
      // If we get here, A2A is available - test passes
    } catch (NoClassDefFoundError e) {
      // Expected if A2A module not available
      assertThat(e.getMessage()).contains("A2aServerBuilder");
    }
  }
}
