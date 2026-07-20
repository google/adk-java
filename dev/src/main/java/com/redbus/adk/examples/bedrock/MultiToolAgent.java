package com.redbus.adk.examples.bedrock;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.factory.ClaudeBedrock;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent implementation that demonstrates using multiple tools with Claude on AWS Bedrock. This
 * class provides examples of different ways to configure and use Claude with Bedrock.
 *
 * @author Akshaya Rawat
 * @version 1.0
 * @since 2025-07-15
 */
public class MultiToolAgent {
  private static final Logger log = LoggerFactory.getLogger(MultiToolAgent.class);

  private static final String NAME = "multi_tool_agent";

  /**
   * Gets the current time for a specified city.
   *
   * @param city the name of the city for which to retrieve the current time
   * @return a map containing status and report with the current time information
   */
  public static Map<String, String> getCurrentTime(
      @Schema(description = "The name of the city for which to retrieve the current time")
          String city) {
    String normalizedCity =
        Normalizer.normalize(city, Normalizer.Form.NFD)
            .trim()
            .toLowerCase()
            .replaceAll("(\\p{IsM}+|\\p{IsP}+)", "")
            .replaceAll("\\s+", "_");

    return ZoneId.getAvailableZoneIds().stream()
        .filter(zid -> zid.toLowerCase().endsWith("/" + normalizedCity))
        .findFirst()
        .map(
            zid ->
                Map.of(
                    "status",
                    "success",
                    "report",
                    "The current time in "
                        + city
                        + " is "
                        + ZonedDateTime.now(ZoneId.of(zid))
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                        + "."))
        .orElse(
            Map.of(
                "status",
                "error",
                "report",
                "Sorry, I don't have timezone information for " + city + "."));
  }

  /**
   * Gets the weather information for a specified city. Currently only supports New York as a demo.
   *
   * @param city the name of the city for which to retrieve the weather report
   * @return a map containing status and report with the weather information
   */
  public static Map<String, String> getWeather(
      @Schema(description = "The name of the city for which to retrieve the weather report")
          String city) {
    if (city.equalsIgnoreCase("new york")) {
      return Map.of(
          "status",
          "success",
          "report",
          "The weather in New York is sunny with a temperature of 25 degrees Celsius (77 degrees"
              + " Fahrenheit).");

    } else {
      return Map.of(
          "status", "error", "report", "Weather information for " + city + " is not available.");
    }
  }

  /**
   * You pass both model id and the region. AWS credentials are read from env variable or aws
   * credentials file (based on the profile).
   *
   * @return a BaseAgent instance configured with Claude on Bedrock using specified model and region
   */
  public BaseAgent exampleOneAgent() {

    return LlmAgent.builder()
        .name(NAME)
        .model(ClaudeBedrock.create("us.anthropic.claude-3-7-sonnet-20250219-v1:0", "us-east-1"))
        .description("Agent to answer questions about the time and weather in a city.")
        .instruction(
            "You are a helpful agent who can answer user questions about the time and weather"
                + " in a city.")
        .tools(
            FunctionTool.create(MultiToolAgent.class, "getCurrentTime"),
            FunctionTool.create(MultiToolAgent.class, "getWeather"))
        .build();
  }

  /**
   * You pass model id. Region is read from AWS_REGION and AWS_DEFAULT_REGION in that order. AWS
   * credentials are read from env variable or aws credentials file (based on the profile).
   *
   * @return a BaseAgent instance configured with Claude on Bedrock using specified model and
   *     default region
   */
  public BaseAgent exampleTwoAgent() {
    return LlmAgent.builder()
        .name(NAME)
        .model(ClaudeBedrock.create("apac.anthropic.claude-3-7-sonnet-20250219-v1:0"))
        .description("Agent to answer questions about the time and weather in a city.")
        .instruction(
            "You are a helpful agent who can answer user questions about the time and weather"
                + " in a city.")
        .tools(
            FunctionTool.create(MultiToolAgent.class, "getCurrentTime"),
            FunctionTool.create(MultiToolAgent.class, "getWeather"))
        .build();
  }

  /**
   * You pass neither the model id nor the region. Model id is read from CLAUDE_MODEL env variable ,
   * if not found defaulted to ClaudeBedrock.DEFAULT_BEDROCK_CLAUDE_MODEL_ID. Region is read from
   * AWS_REGION and AWS_DEFAULT_REGION in that order. AWS credentials are read from env variable or
   * aws credentials file (based on the profile).
   *
   * @return a BaseAgent instance configured with Claude on Bedrock using default model and region
   */
  public BaseAgent exampleThreeAgent() {

    return LlmAgent.builder()
        .name(NAME)
        .model(ClaudeBedrock.create())
        .description("Agent to answer questions about the time and weather in a city.")
        .instruction(
            "You are a helpful agent who can answer user questions about the time and weather"
                + " in a city.")
        .tools(
            FunctionTool.create(MultiToolAgent.class, "getCurrentTime"),
            FunctionTool.create(MultiToolAgent.class, "getWeather"))
        .build();
  }

  /**
   * Main method to run the MultiToolAgent example. Accepts an optional argument to specify which
   * example to run (1, 2, or 3).
   *
   * @param args command line arguments, first argument can be 1, 2, or 3 to select the example
   * @throws Exception if an error occurs during execution
   */
  public static void main(String[] args) throws Exception {
    MultiToolAgent multiToolAgent = new MultiToolAgent();
    String exampleNo = "1";
    if (args.length > 0) {
      exampleNo = args[0];
    }
    log.info("Running example no " + exampleNo);
    BaseAgent rootAgent =
        switch (exampleNo) {
          case "2" -> multiToolAgent.exampleTwoAgent();
          case "3" -> multiToolAgent.exampleThreeAgent();
          default -> multiToolAgent.exampleOneAgent();
        };
    InMemoryRunner runner = new InMemoryRunner(rootAgent);

    String USER_ID = "student";
    Session session = runner.sessionService().createSession(NAME, USER_ID).blockingGet();

    try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
      while (true) {
        System.out.print("\nYou > ");
        String userInput = scanner.nextLine();

        if ("quit".equalsIgnoreCase(userInput)) {
          break;
        }

        Content userMsg = Content.fromParts(Part.fromText(userInput));
        Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg);

        System.out.print("\nAgent > ");
        events.blockingForEach(event -> System.out.println(event.stringifyContent()));
      }
    }
  }
}
