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

package com.google.adk.tutorials.planner.goap;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.PlannerAgent;
import com.google.adk.planner.goap.AgentMetadata;
import com.google.adk.planner.goap.GoalOrientedPlanner;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Map;

/**
 * GOAP Horoscope Writer Example.
 *
 * <p>Demonstrates Goal-Oriented Action Planning (GOAP) where agent execution order is automatically
 * resolved from input/output dependency declarations.
 *
 * <p>Agent dependency graph:
 *
 * <pre>
 *   personExtractor (prompt → person)
 *   signExtractor   (prompt → sign)
 *   horoscopeGenerator (person, sign → horoscope)
 *   writer          (person, horoscope → writeup)
 * </pre>
 *
 * <p>The GOAP planner automatically resolves: personExtractor → signExtractor → horoscopeGenerator
 * → writer
 *
 * <p>Usage: Set the GOOGLE_API_KEY environment variable and run with initial state: {@code
 * {"prompt": "My name is Mario and my zodiac sign is pisces"}}
 */
public class HoroscopeWriterExample {

  // Tool methods that write to session state
  public static Map<String, String> savePerson(
      @Schema(name = "person", description = "The extracted person's name") String person) {
    return Map.of("person", person);
  }

  public static Map<String, String> saveSign(
      @Schema(name = "sign", description = "The extracted zodiac sign") String sign) {
    return Map.of("sign", sign);
  }

  public static Map<String, String> saveHoroscope(
      @Schema(name = "horoscope", description = "The generated horoscope") String horoscope) {
    return Map.of("horoscope", horoscope);
  }

  public static Map<String, String> saveWriteup(
      @Schema(name = "writeup", description = "The final amusing horoscope writeup")
          String writeup) {
    return Map.of("writeup", writeup);
  }

  public static final BaseAgent ROOT_AGENT = createAgent();

  private static BaseAgent createAgent() {
    LlmAgent personExtractor =
        LlmAgent.builder()
            .name("personExtractor")
            .description("Extract person's name from the prompt")
            .model("gemini-2.0-flash")
            .instruction(
                "Extract the person's name from the user's prompt and save it using the"
                    + " savePerson tool. The prompt is: {prompt}")
            .tools(FunctionTool.create(HoroscopeWriterExample.class, "savePerson"))
            .build();

    LlmAgent signExtractor =
        LlmAgent.builder()
            .name("signExtractor")
            .description("Extract zodiac sign from the prompt")
            .model("gemini-2.0-flash")
            .instruction(
                "Extract the zodiac sign from the user's prompt and save it using the saveSign"
                    + " tool. The prompt is: {prompt}")
            .tools(FunctionTool.create(HoroscopeWriterExample.class, "saveSign"))
            .build();

    LlmAgent horoscopeGenerator =
        LlmAgent.builder()
            .name("horoscopeGenerator")
            .description("Generate a horoscope based on person and sign")
            .model("gemini-2.0-flash")
            .instruction(
                "Generate a horoscope for person={person} with zodiac sign={sign}. Save it using"
                    + " the saveHoroscope tool.")
            .tools(FunctionTool.create(HoroscopeWriterExample.class, "saveHoroscope"))
            .build();

    LlmAgent writer =
        LlmAgent.builder()
            .name("writer")
            .description("Write amusing horoscope writeup")
            .model("gemini-2.0-flash")
            .instruction(
                "Write an amusing horoscope writeup for person={person} based on their"
                    + " horoscope={horoscope}. Make it fun and entertaining. Save using the"
                    + " saveWriteup tool.")
            .tools(FunctionTool.create(HoroscopeWriterExample.class, "saveWriteup"))
            .build();

    List<AgentMetadata> metadata =
        List.of(
            new AgentMetadata("personExtractor", ImmutableList.of("prompt"), "person"),
            new AgentMetadata("signExtractor", ImmutableList.of("prompt"), "sign"),
            new AgentMetadata(
                "horoscopeGenerator", ImmutableList.of("person", "sign"), "horoscope"),
            new AgentMetadata("writer", ImmutableList.of("person", "horoscope"), "writeup"));

    return PlannerAgent.builder()
        .name("horoscopeWriter")
        .description("Generates personalized amusing horoscopes using GOAP planning")
        .subAgents(personExtractor, signExtractor, horoscopeGenerator, writer)
        .planner(new GoalOrientedPlanner("writeup", metadata))
        .build();
  }

  public static void main(String[] args) {
    AdkWebServer.start(ROOT_AGENT);
  }
}
