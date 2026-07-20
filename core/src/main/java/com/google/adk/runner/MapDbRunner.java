/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.MapDbArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.memory.MapDBMemoryService;
import com.google.adk.sessions.MapDbSessionService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** The class for the in-memory GenAi runner, using in-memory artifact and session services. */
public class MapDbRunner extends Runner {

  public MapDbRunner(BaseAgent agent) throws IOException {
    // TODO: Change the default appName to InMemoryRunner to align with adk python.
    // Check the dev UI in case we break something there.
    this(agent, /* appName= */ agent.name());
  }

  public MapDbRunner(BaseAgent agent, String appName) throws IOException {
    super(
        agent,
        appName,
        new MapDbArtifactService(appName + "_ART"),
        new MapDbSessionService(appName),
        new InMemoryMemoryService());
  }

  public MapDbRunner(BaseAgent agent, String appName, MapDBMemoryService mapDBMemoryService)
      throws IOException {
    super(
        agent,
        appName,
        new MapDbArtifactService(appName + "_ART"),
        new MapDbSessionService(appName),
        mapDBMemoryService);
  }

  /**
   * Exports all session and state data to a JSON file.
   *
   * @param path The path to the output JSON file.
   * @throws IOException If an I/O error occurs during writing.
   */
  public void exportToJson(Path path) throws IOException {
    if (sessionService() instanceof MapDbSessionService) {
      MapDbSessionService service = (MapDbSessionService) sessionService();
      Map<String, Object> data = service.getAllData();
      ObjectMapper mapper = new ObjectMapper();
      mapper.enable(SerializationFeature.INDENT_OUTPUT);
      mapper.findAndRegisterModules();
      mapper.writeValue(path.toFile(), data);
    } else {
      throw new IllegalStateException("Session service is not an instance of MapDbSessionService");
    }
  }
}
