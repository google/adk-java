package com.google.adk.flows.llmflows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.LlmRequest;
import com.google.adk.planners.BuiltInPlanner;
import com.google.adk.testing.TestLlm;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import org.junit.Test;

import java.util.List;

import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;

public class NlPlanningTest {
    @Test
    public void builtInPlanner_contentListUnchanged() {

        Content content = Content.fromParts(Part.fromText("LLM response"));
        TestLlm testLlm = createTestLlm(createLlmResponse(content));
        BuiltInPlanner planner = BuiltInPlanner.buildPlanner(ThinkingConfig.builder().build());
        LlmAgent agent = createTestAgent(testLlm, planner);
        var invocationContext = com.google.adk.testing.TestUtils.createInvocationContext(agent);

        List<Content> contents = List.of(
            Content.builder()
                .role("user")
                .parts(Part.fromText("Hello"))
                .build(),
            Content.builder()
                .role("model")
                .parts(
                    Part.builder().thought(true).text("thinking....").build(),
                    Part.fromText("Here is my response")
                )
                .build(),
            Content.builder()
                .role("user")
                .parts(Part.fromText("Follow up"))
                .build()
        );
        LlmRequest llmRequest = LlmRequest.builder().contents(contents).build();
        List<Content> originalContents = List.copyOf(llmRequest.contents());

        // Act
        agent.runAsync(invocationContext).blockingSubscribe();

        // Assert
        assertThat(llmRequest.contents()).isEqualTo(originalContents);
    }
}
