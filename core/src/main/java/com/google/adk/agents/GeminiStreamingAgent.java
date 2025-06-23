package agents;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import io.reactivex.rxjava3.core.Flowable;

public class GeminiStreamingAgent {
  public static BaseAgent ROOT_AGENT = initAgent();

  private static BaseAgent initAgent() {
    return LlmAgent.builder()
        .name("gemini-streaming-agent")
        .description("Agent demonstrating Gemini Live streaming")
        .model("gemini-2.0-flash-live-001")
        .instruction("Respond in streaming mode. Use concise messages.")
        .build();
  }

  public static void main(String[] args) {
    InMemorySessionService sessionService = new InMemorySessionService();
    Runner runner = new Runner(ROOT_AGENT, "GeminiLiveApp", null, sessionService);

    var session = sessionService.createSession("GeminiLiveApp", "user1").blockingGet();

    // Demonstrate streaming via console
    Flowable<Event> stream = runner.runLive(session.userId(), session.id(), "What's trending in AI today?");

    stream.subscribe(event -> {
      System.out.print(event.stringifyContent());
    }, err -> {
      err.printStackTrace();
    }, () -> {
      System.out.println("\n［Stream Complete］");
    });
  }
}
