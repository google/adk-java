package com.google.adk.models;

import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.langchain4j.model.anthropic.AnthropicChatModelName.CLAUDE_3_7_SONNET_20250219;

public class LangChain4jTest {
    @Test
    void testSimpleAgent() {
        // given
        AnthropicChatModel claudeModel = AnthropicChatModel.builder()
            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
            .modelName(CLAUDE_3_7_SONNET_20250219)
            .build();

        LlmAgent agent = LlmAgent.builder()
            .name("science-app")
            .description("Science teacher agent")
            .model(new LangChain4j(claudeModel, CLAUDE_3_7_SONNET_20250219.toString()))
            .instruction("""
                You are a helpful science teacher that explains science concepts
                to kids and teenagers.
                """)
            .build();

        // when
        List<Event> events = askAgent(agent, "What is a qubit?");

        // then
        assertEquals(1, events.size());

        Event firstEvent = events.get(0);
        assertTrue(firstEvent.content().isPresent());

        Content content = firstEvent.content().get();
        System.out.println("Answer: " + content.text());
        assertTrue(content.text().contains("quantum"));
    }

    @Test
    void testSingleAgentWithTools() {
        // given
        AnthropicChatModel claudeModel = AnthropicChatModel.builder()
            .apiKey(System.getenv("ANTHROPIC_API_KEY"))
            .modelName(CLAUDE_3_7_SONNET_20250219)
            .build();

        BaseAgent agent = LlmAgent.builder()
            .name("friendly-weather-app")
            .description("Friend agent that knows about the weather")
            .model(new LangChain4j(claudeModel, CLAUDE_3_7_SONNET_20250219.toString()))
            .instruction("""
                You are a friendly assistant.
                
                If asked about the weather forecast for a city,
                you MUST call the `getWeather` function.
                """)
            .tools(FunctionTool.create(LangChain4jTest.class, "getWeather"))
            .build();

        // when
        List<Event> events = askAgent(agent, "What's the weather like in Paris?");

        // then
        assertEquals(3, events.size());

        events.forEach(event -> {
            assertTrue(event.content().isPresent());
            System.out.printf("%nevent: %s%n", event.stringifyContent());
        });

        Event eventOne = events.get(0);
        Event eventTwo = events.get(1);
        Event eventThree = events.get(2);

        // assert the first event is a function call
        Content contentOne = eventOne.content().get();
        assertTrue(contentOne.parts().isPresent());
        List<Part> partsOne = contentOne.parts().get();
        assertEquals(1, partsOne.size());
        Optional<FunctionCall> functionCall = partsOne.get(0).functionCall();
        assertTrue(functionCall.isPresent());
        assertEquals("getWeather", functionCall.get().name().get());
        assertTrue(functionCall.get().args().get().containsKey("city"));

        // assert the second event is a function response
        Content contentTwo = eventTwo.content().get();
        assertTrue(contentTwo.parts().isPresent());
        List<Part> partsTwo = contentTwo.parts().get();
        assertEquals(1, partsTwo.size());
        Optional<FunctionResponse> functionResponseTwo = partsTwo.get(0).functionResponse();
        assertTrue(functionResponseTwo.isPresent());
        assertTrue(functionResponseTwo.get().response().get().get("city").toString().contains("Paris"));
        assertTrue(functionResponseTwo.get().response().get().get("forecast").toString().contains("sunny"));
        assertTrue(functionResponseTwo.get().response().get().get("temperature").toString().contains("24"));

        // assert the third event is the final text response
        assertTrue(eventThree.finalResponse());
        Content contentThree = eventThree.content().get();
        assertTrue(contentThree.parts().isPresent());
        List<Part> partsThree = contentThree.parts().get();
        assertEquals(1, partsThree.size());
        assertTrue(partsThree.get(0).text().get().contains("beautiful"));
    }

    private static List<Event> askAgent(BaseAgent agent, String... messages) {
        ArrayList<Event> allEvents = new ArrayList<>();

        Runner runner = new InMemoryRunner(agent, agent.name());
        Session session = runner.sessionService().createSession(agent.name(), "user132").blockingGet();

        for (String message : messages) {
            Content messageContent = Content.fromParts(Part.fromText(message));
            allEvents.addAll(
                runner.runAsync(session, messageContent, RunConfig.builder().build())
                    .blockingStream().toList()
            );
        }

        return allEvents;
    }

    @Schema(description = "Function to get the weather forecast for a given city")
    public static Map<String, String> getWeather(
        @Schema(name = "city", description = "The city to get the weather forecast for")
        String city) {
        return Map.of(
            "city", city,
            "forecast", "a beautiful and sunny weather",
            "temperature", "from 10°C in the morning up to 24°C in the afternoon"
        );
    }
}
