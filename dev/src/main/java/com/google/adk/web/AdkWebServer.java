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

package com.google.adk.web;

import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Modality;
import com.google.genai.types.Part;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Single-file Spring Boot application for the Agent Server. Combines configuration, DTOs, and
 * controller logic.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AdkWebServer implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(AdkWebServer.class);

  // WebSocket constants
  private static final String LIVE_REQUEST_QUEUE_ATTR = "liveRequestQueue";
  private static final String LIVE_SUBSCRIPTION_ATTR = "liveSubscription";
  private static final int WEBSOCKET_MAX_BYTES_FOR_REASON = 123;
  private static final int WEBSOCKET_PROTOCOL_ERROR = 1002;
  private static final int WEBSOCKET_INTERNAL_SERVER_ERROR = 1011;

  // Session constants
  private static final String EVAL_SESSION_ID_PREFIX = "ADK_EVAL_";

  @Value("${adk.web.ui.dir:#{null}}")
  private String webUiDir;

  @Bean
  public BaseSessionService sessionService() {
    // TODO: Add logic to select service based on config (e.g., DB URL)
    log.info("Using InMemorySessionService");
    return new InMemorySessionService();
  }

  /**
   * Provides the singleton instance of the ArtifactService (InMemory). TODO: configure this based
   * on config (e.g., DB URL)
   *
   * @return An instance of BaseArtifactService (currently InMemoryArtifactService).
   */
  @Bean
  public BaseArtifactService artifactService() {
    log.info("Using InMemoryArtifactService");
    return new InMemoryArtifactService();
  }

  /**
   * Provides the singleton instance of the MemoryService (InMemory). Will be made configurable once
   * we have the Vertex MemoryService.
   *
   * @return An instance of BaseMemoryService (currently InMemoryMemoryService).
   */
  @Bean
  public BaseMemoryService memoryService() {
    log.info("Using InMemoryMemoryService");
    return new InMemoryMemoryService();
  }

  /**
   * Configures the Jackson ObjectMapper for JSON serialization. Uses the ADK standard mapper
   * configuration.
   *
   * @return Configured ObjectMapper instance
   */
  @Bean
  public ObjectMapper objectMapper() {
    return JsonBaseModel.getMapper();
  }

  /** Service for creating and caching Runner instances. */
  @Component
  public static class RunnerService {
    private static final Logger log = LoggerFactory.getLogger(RunnerService.class);

    private final AgentLoader agentProvider;
    private final BaseArtifactService artifactService;
    private final BaseSessionService sessionService;
    private final BaseMemoryService memoryService;
    private final Map<String, Runner> runnerCache = new ConcurrentHashMap<>();

    @Autowired
    public RunnerService(
        AgentLoader agentProvider,
        BaseArtifactService artifactService,
        BaseSessionService sessionService,
        BaseMemoryService memoryService) {
      this.agentProvider = agentProvider;
      this.artifactService = artifactService;
      this.sessionService = sessionService;
      this.memoryService = memoryService;
    }

    /**
     * Gets the Runner instance for a given application name. Handles potential agent engine ID
     * overrides.
     *
     * @param appName The application name requested by the user.
     * @return A configured Runner instance.
     */
    public Runner getRunner(String appName) {
      return runnerCache.computeIfAbsent(
          appName,
          key -> {
            try {
              BaseAgent agent = agentProvider.loadAgent(key);
              log.info(
                  "RunnerService: Creating Runner for appName: {}, using agent definition: {}",
                  appName,
                  agent.name());
              return new Runner(
                  agent, appName, this.artifactService, this.sessionService, this.memoryService);
            } catch (java.util.NoSuchElementException e) {
              log.error(
                  "Agent/App named '{}' not found in registry. Available apps: {}",
                  key,
                  agentProvider.listAgents());
              throw new ResponseStatusException(
                  HttpStatus.NOT_FOUND, "Agent/App not found: " + key);
            } catch (IllegalStateException e) {
              log.error("Agent '{}' exists but failed to load: {}", key, e.getMessage());
              throw new ResponseStatusException(
                  HttpStatus.INTERNAL_SERVER_ERROR, "Agent failed to load: " + key, e);
            }
          });
    }

    /** Called by hot loader when agents are updated */
    public void onAgentUpdated(String agentName) {
      Runner removed = runnerCache.remove(agentName);
      if (removed != null) {
        log.info("Cleared cached Runner for updated agent: {}", agentName);
      } else {
        log.debug("No cached Runner found for agent: {}", agentName);
      }
    }
  }

  /** Configuration class for OpenTelemetry, setting up the tracer provider and span exporter. */
  @Configuration
  public static class OpenTelemetryConfig {
    private static final Logger otelLog = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @Bean
    public ApiServerSpanExporter apiServerSpanExporter() {
      return new ApiServerSpanExporter();
    }

    @Bean(destroyMethod = "shutdown")
    public SdkTracerProvider sdkTracerProvider(ApiServerSpanExporter apiServerSpanExporter) {
      otelLog.debug("Configuring SdkTracerProvider with ApiServerSpanExporter.");
      Resource resource =
          Resource.getDefault()
              .merge(
                  Resource.create(
                      Attributes.of(AttributeKey.stringKey("service.name"), "adk-web-server")));

      return SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(apiServerSpanExporter))
          .setResource(resource)
          .build();
    }

    @Bean
    public OpenTelemetry openTelemetrySdk(SdkTracerProvider sdkTracerProvider) {
      otelLog.debug("Configuring OpenTelemetrySdk and registering globally.");

      // Check if OpenTelemetry has already been set globally (common in tests)
      try {
        io.opentelemetry.api.GlobalOpenTelemetry.get();
        // If we get here, it's already set, so just return a new instance without global
        // registration
        otelLog.debug("OpenTelemetry already registered globally, creating non-global instance.");
        return OpenTelemetrySdk.builder().setTracerProvider(sdkTracerProvider).build();
      } catch (IllegalStateException e) {
        // GlobalOpenTelemetry hasn't been set yet, safe to register globally
        otelLog.debug("Registering OpenTelemetry globally.");
        OpenTelemetrySdk otelSdk =
            OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .buildAndRegisterGlobal();
        Runtime.getRuntime().addShutdownHook(new Thread(otelSdk::close));
        return otelSdk;
      }
    }
  }

  /**
   * A custom SpanExporter that stores relevant span data. It handles two types of trace data
   * storage: 1. Event-ID based: Stores attributes of specific spans (call_llm, send_data,
   * tool_response) keyed by `gcp.vertex.agent.event_id`. This is used for debugging individual
   * events. 2. Session-ID based: Stores all exported spans and maintains a mapping from
   * `session_id` (extracted from `call_llm` spans) to a list of `trace_id`s. This is used for
   * retrieving all spans related to a session.
   */
  public static class ApiServerSpanExporter implements SpanExporter {
    private static final Logger exporterLog = LoggerFactory.getLogger(ApiServerSpanExporter.class);

    private final Map<String, Map<String, Object>> eventIdTraceStorage = new ConcurrentHashMap<>();

    // Session ID -> Trace IDs -> Trace Object
    private final Map<String, List<String>> sessionToTraceIdsMap = new ConcurrentHashMap<>();

    private final List<SpanData> allExportedSpans = Collections.synchronizedList(new ArrayList<>());

    public ApiServerSpanExporter() {}

    public Map<String, Object> getEventTraceAttributes(String eventId) {
      return this.eventIdTraceStorage.get(eventId);
    }

    public Map<String, List<String>> getSessionToTraceIdsMap() {
      return this.sessionToTraceIdsMap;
    }

    public List<SpanData> getAllExportedSpans() {
      return this.allExportedSpans;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      exporterLog.debug("ApiServerSpanExporter received {} spans to export.", spans.size());
      List<SpanData> currentBatch = new ArrayList<>(spans);
      allExportedSpans.addAll(currentBatch);

      for (SpanData span : currentBatch) {
        String spanName = span.getName();
        if ("call_llm".equals(spanName)
            || "send_data".equals(spanName)
            || (spanName != null && spanName.startsWith("tool_response"))) {
          String eventId =
              span.getAttributes().get(AttributeKey.stringKey("gcp.vertex.agent.event_id"));
          if (eventId != null && !eventId.isEmpty()) {
            Map<String, Object> attributesMap = new HashMap<>();
            span.getAttributes().forEach((key, value) -> attributesMap.put(key.getKey(), value));
            attributesMap.put("trace_id", span.getSpanContext().getTraceId());
            attributesMap.put("span_id", span.getSpanContext().getSpanId());
            attributesMap.putIfAbsent("gcp.vertex.agent.event_id", eventId);
            exporterLog.debug("Storing event-based trace attributes for event_id: {}", eventId);
            this.eventIdTraceStorage.put(eventId, attributesMap); // Use internal storage
          } else {
            exporterLog.trace(
                "Span {} for event-based trace did not have 'gcp.vertex.agent.event_id'"
                    + " attribute or it was empty.",
                spanName);
          }
        }

        if ("call_llm".equals(spanName)) {
          String sessionId =
              span.getAttributes().get(AttributeKey.stringKey("gcp.vertex.agent.session_id"));
          if (sessionId != null && !sessionId.isEmpty()) {
            String traceId = span.getSpanContext().getTraceId();
            sessionToTraceIdsMap
                .computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(traceId);
            exporterLog.trace(
                "Associated trace_id {} with session_id {} for session tracing",
                traceId,
                sessionId);
          } else {
            exporterLog.trace(
                "Span {} for session trace did not have 'gcp.vertex.agent.session_id' attribute.",
                spanName);
          }
        }
      }
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      exporterLog.debug("Shutting down ApiServerSpanExporter.");
      // no need to clear storage on shutdown, as everything is currently stored in memory.
      return CompletableResultCode.ofSuccess();
    }
  }

  /**
   * Data Transfer Object (DTO) for POST /run and POST /run-sse requests. Contains information
   * needed to execute an agent run.
   */
  public static class AgentRunRequest {
    @JsonProperty("appName")
    public String appName;

    @JsonProperty("userId")
    public String userId;

    @JsonProperty("sessionId")
    public String sessionId;

    @JsonProperty("newMessage")
    public Content newMessage;

    @JsonProperty("streaming")
    public boolean streaming = false;

    public AgentRunRequest() {}

    public String getAppName() {
      return appName;
    }

    public String getUserId() {
      return userId;
    }

    public String getSessionId() {
      return sessionId;
    }

    public Content getNewMessage() {
      return newMessage;
    }

    public boolean getStreaming() {
      return streaming;
    }
  }

  /**
   * DTO for POST /apps/{appName}/eval_sets/{evalSetId}/add-session requests. Contains information
   * to associate a session with an evaluation set.
   */
  public static class AddSessionToEvalSetRequest {
    @JsonProperty("evalId")
    public String evalId;

    @JsonProperty("sessionId")
    public String sessionId;

    @JsonProperty("userId")
    public String userId;

    public AddSessionToEvalSetRequest() {}

    public String getEvalId() {
      return evalId;
    }

    public String getSessionId() {
      return sessionId;
    }

    public String getUserId() {
      return userId;
    }
  }

  /**
   * DTO for POST /apps/{appName}/eval_sets/{evalSetId}/run-eval requests. Contains information for
   * running evaluations.
   */
  public static class RunEvalRequest {
    @JsonProperty("evalIds")
    public List<String> evalIds;

    @JsonProperty("evalMetrics")
    public List<String> evalMetrics;

    public RunEvalRequest() {}

    public List<String> getEvalIds() {
      return evalIds;
    }

    public List<String> getEvalMetrics() {
      return evalMetrics;
    }
  }

  /**
   * DTO for the response of POST /apps/{appName}/eval_sets/{evalSetId}/run-eval. Contains the
   * results of an evaluation run.
   */
  public static class RunEvalResult extends JsonBaseModel {
    @JsonProperty("appName")
    public String appName;

    @JsonProperty("evalSetId")
    public String evalSetId;

    @JsonProperty("evalId")
    public String evalId;

    @JsonProperty("finalEvalStatus")
    public String finalEvalStatus;

    @JsonProperty("evalMetricResults")
    public List<List<Object>> evalMetricResults;

    @JsonProperty("sessionId")
    public String sessionId;

    /**
     * Constructs a RunEvalResult.
     *
     * @param appName The application name.
     * @param evalSetId The evaluation set ID.
     * @param evalId The evaluation ID.
     * @param finalEvalStatus The final status of the evaluation.
     * @param evalMetricResults The results for each metric.
     * @param sessionId The session ID associated with the evaluation.
     */
    public RunEvalResult(
        String appName,
        String evalSetId,
        String evalId,
        String finalEvalStatus,
        List<List<Object>> evalMetricResults,
        String sessionId) {
      this.appName = appName;
      this.evalSetId = evalSetId;
      this.evalId = evalId;
      this.finalEvalStatus = finalEvalStatus;
      this.evalMetricResults = evalMetricResults;
      this.sessionId = sessionId;
    }

    public RunEvalResult() {}
  }

  /**
   * DTO for the response of GET
   * /apps/{appName}/users/{userId}/sessions/{sessionId}/events/{eventId}/graph. Contains the graph
   * representation (e.g., DOT source).
   */
  public static class GraphResponse {
    @JsonProperty("dotSrc")
    public String dotSrc;

    /**
     * Constructs a GraphResponse.
     *
     * @param dotSrc The graph source string (e.g., in DOT format).
     */
    public GraphResponse(String dotSrc) {
      this.dotSrc = dotSrc;
    }

    public GraphResponse() {}

    public String getDotSrc() {
      return dotSrc;
    }
  }

  /**
   * Configures resource handlers for serving static content (like the Dev UI). Maps requests
   * starting with "/dev-ui/" to the directory specified by the 'adk.web.ui.dir' system property.
   */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (webUiDir != null && !webUiDir.isEmpty()) {
      // Ensure the path uses forward slashes and ends with a slash
      String location = webUiDir.replace("\\", "/");
      if (!location.startsWith("file:")) {
        location = "file:" + location; // Ensure file: prefix
      }
      if (!location.endsWith("/")) {
        location += "/";
      }
      log.debug("Mapping URL path /** to static resources at location: {}", location);
      registry
          .addResourceHandler("/**")
          .addResourceLocations(location)
          .setCachePeriod(0)
          .resourceChain(true);

    } else {
      log.debug(
          "System property 'adk.web.ui.dir' or config 'adk.web.ui.dir' is not set. Mapping URL path"
              + " /** to classpath:/browser/");
      registry
          .addResourceHandler("/**")
          .addResourceLocations("classpath:/browser/")
          .setCachePeriod(0)
          .resourceChain(true);
    }
  }

  /**
   * Configures simple automated controllers: - Redirects the root path "/" to "/dev-ui". - Forwards
   * requests to "/dev-ui" to "/dev-ui/index.html" so the ResourceHandler serves it.
   */
  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/", "/dev-ui");
    registry.addViewController("/dev-ui").setViewName("forward:/index.html");
    registry.addViewController("/dev-ui/").setViewName("forward:/index.html");
  }

  /** Spring Boot REST Controller handling agent-related API endpoints. */
  @RestController
  public static class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final BaseSessionService sessionService;
    private final BaseArtifactService artifactService;
    private final AgentLoader agentProvider;
    private final ApiServerSpanExporter apiServerSpanExporter;
    private final RunnerService runnerService;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * Constructs the AgentController.
     *
     * @param sessionService The service for managing sessions.
     * @param artifactService The service for managing artifacts.
     * @param agentProvider The provider for loading agents.
     * @param apiServerSpanExporter The exporter holding all trace data.
     * @param runnerService The service for obtaining Runner instances.
     */
    @Autowired
    public AgentController(
        BaseSessionService sessionService,
        BaseArtifactService artifactService,
        AgentLoader agentProvider,
        ApiServerSpanExporter apiServerSpanExporter,
        RunnerService runnerService) {
      this.sessionService = sessionService;
      this.artifactService = artifactService;
      this.agentProvider = agentProvider;
      this.apiServerSpanExporter = apiServerSpanExporter;
      this.runnerService = runnerService;
      ImmutableList<String> agentNames = this.agentProvider.listAgents();
      log.info(
          "AgentController initialized with {} dynamic agents: {}", agentNames.size(), agentNames);
      if (agentNames.isEmpty()) {
        log.warn(
            "Agent registry is empty. Check 'adk.agents.source-dir' property and compilation"
                + " logs.");
      }
    }

    /**
     * Finds a session by its identifiers or throws a ResponseStatusException if not found or if
     * there's an app/user mismatch.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The session ID.
     * @return The found Session object.
     * @throws ResponseStatusException with HttpStatus.NOT_FOUND if the session doesn't exist or
     *     belongs to a different app/user.
     */
    private Session findSessionOrThrow(String appName, String userId, String sessionId) {
      Maybe<Session> maybeSession =
          sessionService.getSession(appName, userId, sessionId, Optional.empty());

      Session session = maybeSession.blockingGet();

      if (session == null) {
        log.warn(
            "Session not found for appName={}, userId={}, sessionId={}",
            appName,
            userId,
            sessionId);
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            String.format(
                "Session not found: appName=%s, userId=%s, sessionId=%s",
                appName, userId, sessionId));
      }

      if (!Objects.equals(session.appName(), appName)
          || !Objects.equals(session.userId(), userId)) {
        log.warn(
            "Session ID {} found but appName/userId mismatch (Expected: {}/{}, Found: {}/{}) -"
                + " Treating as not found.",
            sessionId,
            appName,
            userId,
            session.appName(),
            session.userId());

        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Session found but belongs to a different app/user.");
      }
      log.debug("Found session: {}", sessionId);
      return session;
    }

    /**
     * Lists available applications. Currently returns only the configured root agent's name.
     *
     * @return A list containing the root agent's name.
     */
    @GetMapping("/list-apps")
    public List<String> listApps() {
      ImmutableList<String> agentNames = agentProvider.listAgents();
      log.info("Listing apps from dynamic registry. Found: {}", agentNames);
      return agentNames.stream().sorted().collect(toList());
    }

    /**
     * Endpoint for retrieving trace information stored by the ApiServerSpanExporter, based on event
     * ID.
     *
     * @param eventId The ID of the event to trace (expected to be gcp.vertex.agent.event_id).
     * @return A ResponseEntity containing the trace data or NOT_FOUND.
     */
    @GetMapping("/debug/trace/{eventId}")
    public ResponseEntity<?> getTraceDict(@PathVariable String eventId) {
      log.info("Request received for GET /debug/trace/{}", eventId);
      Map<String, Object> traceData = this.apiServerSpanExporter.getEventTraceAttributes(eventId);
      if (traceData == null) {
        log.warn("Trace not found for eventId: {}", eventId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Collections.singletonMap("message", "Trace not found for eventId: " + eventId));
      }
      log.info("Returning trace data for eventId: {}", eventId);
      return ResponseEntity.ok(traceData);
    }

    /**
     * Retrieves trace spans for a given session ID.
     *
     * @param sessionId The session ID.
     * @return A ResponseEntity containing a list of span data maps for the session, or an empty
     *     list.
     */
    @GetMapping("/debug/trace/session/{sessionId}")
    public ResponseEntity<Object> getSessionTrace(@PathVariable String sessionId) {
      log.info("Request received for GET /debug/trace/session/{}", sessionId);

      List<String> traceIdsForSession =
          this.apiServerSpanExporter.getSessionToTraceIdsMap().get(sessionId);

      if (traceIdsForSession == null || traceIdsForSession.isEmpty()) {
        log.warn("No trace IDs found for session ID: {}", sessionId);
        return ResponseEntity.ok(Collections.emptyList());
      }

      // Iterate over a snapshot of all spans to avoid concurrent modification issues
      // if the exporter is actively adding spans.
      List<SpanData> allSpansSnapshot =
          new ArrayList<>(this.apiServerSpanExporter.getAllExportedSpans());

      if (allSpansSnapshot.isEmpty()) {
        log.warn("No spans have been exported yet overall.");
        return ResponseEntity.ok(Collections.emptyList());
      }

      Set<String> relevantTraceIds = new HashSet<>(traceIdsForSession);
      List<Map<String, Object>> resultSpans = new ArrayList<>();

      for (SpanData span : allSpansSnapshot) {
        if (relevantTraceIds.contains(span.getSpanContext().getTraceId())) {
          Map<String, Object> spanMap = new HashMap<>();
          spanMap.put("name", span.getName());
          spanMap.put("span_id", span.getSpanContext().getSpanId());
          spanMap.put("trace_id", span.getSpanContext().getTraceId());
          spanMap.put("start_time", span.getStartEpochNanos());
          spanMap.put("end_time", span.getEndEpochNanos());

          Map<String, Object> attributesMap = new HashMap<>();
          span.getAttributes().forEach((key, value) -> attributesMap.put(key.getKey(), value));
          spanMap.put("attributes", attributesMap);

          String parentSpanId = span.getParentSpanId();
          if (SpanId.isValid(parentSpanId)) {
            spanMap.put("parent_span_id", parentSpanId);
          } else {
            spanMap.put("parent_span_id", null);
          }
          resultSpans.add(spanMap);
        }
      }

      log.info("Returning {} spans for session ID: {}", resultSpans.size(), sessionId);
      return ResponseEntity.ok(resultSpans);
    }

    /**
     * Retrieves a specific session by its ID.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The session ID.
     * @return The requested Session object.
     * @throws ResponseStatusException if the session is not found.
     */
    @GetMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}")
    public Session getSession(
        @PathVariable String appName, @PathVariable String userId, @PathVariable String sessionId) {
      log.info(
          "Request received for GET /apps/{}/users/{}/sessions/{}", appName, userId, sessionId);
      return findSessionOrThrow(appName, userId, sessionId);
    }

    /**
     * Lists all non-evaluation sessions for a given app and user.
     *
     * @param appName The name of the application.
     * @param userId The ID of the user.
     * @return A list of sessions, excluding those used for evaluation.
     */
    @GetMapping("/apps/{appName}/users/{userId}/sessions")
    public List<Session> listSessions(@PathVariable String appName, @PathVariable String userId) {
      log.info("Request received for GET /apps/{}/users/{}/sessions", appName, userId);

      Single<ListSessionsResponse> sessionsResponseSingle =
          sessionService.listSessions(appName, userId);

      ListSessionsResponse response = sessionsResponseSingle.blockingGet();
      if (response == null || response.sessions() == null) {
        log.warn(
            "Received null response or null sessions list for listSessions({}, {})",
            appName,
            userId);
        return Collections.emptyList();
      }

      List<Session> filteredSessions =
          response.sessions().stream()
              .filter(s -> !s.id().startsWith(EVAL_SESSION_ID_PREFIX))
              .collect(toList());
      log.info(
          "Found {} non-evaluation sessions for app={}, user={}",
          filteredSessions.size(),
          appName,
          userId);
      return filteredSessions;
    }

    /**
     * Creates a new session with a specific ID provided by the client.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The desired session ID.
     * @param state Optional initial state for the session.
     * @return The newly created Session object.
     * @throws ResponseStatusException if a session with the given ID already exists (BAD_REQUEST)
     *     or if creation fails (INTERNAL_SERVER_ERROR).
     */
    @PostMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}")
    public Session createSessionWithId(
        @PathVariable String appName,
        @PathVariable String userId,
        @PathVariable String sessionId,
        @RequestBody(required = false) Map<String, Object> state) {
      log.info(
          "Request received for POST /apps/{}/users/{}/sessions/{} with state: {}",
          appName,
          userId,
          sessionId,
          state);

      try {
        findSessionOrThrow(appName, userId, sessionId);

        log.warn("Attempted to create session with existing ID: {}", sessionId);
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Session already exists: " + sessionId);
      } catch (ResponseStatusException e) {

        if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
          throw e;
        }

        log.info("Session {} not found, proceeding with creation.", sessionId);
      }

      Map<String, Object> initialState = (state != null) ? state : Collections.emptyMap();
      try {
        Session createdSession =
            sessionService
                .createSession(appName, userId, new ConcurrentHashMap<>(initialState), sessionId)
                .blockingGet();

        if (createdSession == null) {

          log.error(
              "Session creation call completed without error but returned null session for {}",
              sessionId);
          throw new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create session (null result)");
        }
        log.info("Session created successfully with id: {}", createdSession.id());
        return createdSession;
      } catch (Exception e) {
        log.error("Error creating session with id {}", sessionId, e);

        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Error creating session", e);
      }
    }

    /**
     * Creates a new session where the ID is generated by the service.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param state Optional initial state for the session.
     * @return The newly created Session object.
     * @throws ResponseStatusException if creation fails (INTERNAL_SERVER_ERROR).
     */
    @PostMapping("/apps/{appName}/users/{userId}/sessions")
    public Session createSession(
        @PathVariable String appName,
        @PathVariable String userId,
        @RequestBody(required = false) Map<String, Object> state) {

      log.info(
          "Request received for POST /apps/{}/users/{}/sessions (service generates ID) with state:"
              + " {}",
          appName,
          userId,
          state);

      Map<String, Object> initialState = (state != null) ? state : Collections.emptyMap();
      try {

        Session createdSession =
            sessionService
                .createSession(appName, userId, new ConcurrentHashMap<>(initialState), null)
                .blockingGet();

        if (createdSession == null) {
          log.error(
              "Session creation call completed without error but returned null session for user {}",
              userId);
          throw new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create session (null result)");
        }
        log.info("Session created successfully with generated id: {}", createdSession.id());
        return createdSession;
      } catch (Exception e) {
        log.error("Error creating session for user {}", userId, e);
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Error creating session", e);
      }
    }

    /**
     * Deletes a specific session.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The session ID to delete.
     * @return A ResponseEntity with status NO_CONTENT on success.
     * @throws ResponseStatusException if deletion fails (INTERNAL_SERVER_ERROR).
     */
    @DeleteMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(
        @PathVariable String appName, @PathVariable String userId, @PathVariable String sessionId) {
      log.info(
          "Request received for DELETE /apps/{}/users/{}/sessions/{}", appName, userId, sessionId);
      try {

        sessionService.deleteSession(appName, userId, sessionId).blockingAwait();
        log.info("Session deleted successfully: {}", sessionId);
        return ResponseEntity.noContent().build();
      } catch (Exception e) {

        log.error("Error deleting session {}", sessionId, e);

        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Error deleting session", e);
      }
    }

    /**
     * Loads the latest or a specific version of an artifact associated with a session.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The session ID.
     * @param artifactName The name of the artifact.
     * @param version Optional specific version number. If null, loads the latest.
     * @return The artifact content as a Part object.
     * @throws ResponseStatusException if the artifact is not found (NOT_FOUND).
     */
    @GetMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}")
    public Part loadArtifact(
        @PathVariable String appName,
        @PathVariable String userId,
        @PathVariable String sessionId,
        @PathVariable String artifactName,
        @RequestParam(required = false) Integer version) {
      String versionStr = (version == null) ? "latest" : String.valueOf(version);
      log.info(
          "Request received to load artifact: app={}, user={}, session={}, artifact={}, version={}",
          appName,
          userId,
          sessionId,
          artifactName,
          versionStr);

      Maybe<Part> artifactMaybe =
          artifactService.loadArtifact(
              appName, userId, sessionId, artifactName, Optional.ofNullable(version));

      Part artifact = artifactMaybe.blockingGet();

      if (artifact == null) {
        log.warn(
            "Artifact not found: app={}, user={}, session={}, artifact={}, version={}",
            appName,
            userId,
            sessionId,
            artifactName,
            versionStr);
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found");
      }
      log.debug("Artifact {} version {} loaded successfully.", artifactName, versionStr);
      return artifact;
    }

    /**
     * Loads a specific version of an artifact.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The session ID.
     * @param artifactName The name of the artifact.
     * @param versionId The specific version number.
     * @return The artifact content as a Part object.
     * @throws ResponseStatusException if the artifact version is not found (NOT_FOUND).
     */
    @GetMapping(
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/versions/{versionId}")
    public Part loadArtifactVersion(
        @PathVariable String appName,
        @PathVariable String userId,
        @PathVariable String sessionId,
        @PathVariable String artifactName,
        @PathVariable int versionId) {
      log.info(
          "Request received to load artifact version: app={}, user={}, session={}, artifact={},"
              + " version={}",
          appName,
          userId,
          sessionId,
          artifactName,
          versionId);

      Maybe<Part> artifactMaybe =
          artifactService.loadArtifact(
              appName, userId, sessionId, artifactName, Optional.of(versionId));

      Part artifact = artifactMaybe.blockingGet();

      if (artifact == null) {
        log.warn(
            "Artifact version not found: app={}, user={}, session={}, artifact={}, version={}",
            appName,
            userId,
            sessionId,
            artifactName,
            versionId);
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact version not found");
      }
      log.debug("Artifact {} version {} loaded successfully.", artifactName, versionId);
      return artifact;
    }

    /**
     * Lists the names of all artifacts associated with a session.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The session ID.
     * @return A list of artifact names.
     */
    @GetMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts")
    public List<String> listArtifactNames(
        @PathVariable String appName, @PathVariable String userId, @PathVariable String sessionId) {
      log.info(
          "Request received to list artifact names for app={}, user={}, session={}",
          appName,
          userId,
          sessionId);

      Single<ListArtifactsResponse> responseSingle =
          artifactService.listArtifactKeys(appName, userId, sessionId);

      ListArtifactsResponse response = responseSingle.blockingGet();
      List<String> filenames =
          (response != null && response.filenames() != null)
              ? response.filenames()
              : Collections.emptyList();
      log.info("Found {} artifact names for session {}", filenames.size(), sessionId);
      return filenames;
    }

    /**
     * Lists the available versions for a specific artifact.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The session ID.
     * @param artifactName The name of the artifact.
     * @return A list of version numbers (integers).
     */
    @GetMapping(
        "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/versions")
    public List<Integer> listArtifactVersions(
        @PathVariable String appName,
        @PathVariable String userId,
        @PathVariable String sessionId,
        @PathVariable String artifactName) {
      log.info(
          "Request received to list versions for artifact: app={}, user={}, session={},"
              + " artifact={}",
          appName,
          userId,
          sessionId,
          artifactName);

      Single<ImmutableList<Integer>> versionsSingle =
          artifactService.listVersions(appName, userId, sessionId, artifactName);
      ImmutableList<Integer> versions = versionsSingle.blockingGet();
      log.info(
          "Found {} versions for artifact {}",
          versions != null ? versions.size() : 0,
          artifactName);
      return versions != null ? versions : Collections.emptyList();
    }

    /**
     * Deletes an artifact and all its versions.
     *
     * @param appName The application name.
     * @param userId The user ID.
     * @param sessionId The session ID.
     * @param artifactName The name of the artifact to delete.
     * @return A ResponseEntity with status NO_CONTENT on success.
     * @throws ResponseStatusException if deletion fails (INTERNAL_SERVER_ERROR).
     */
    @DeleteMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}")
    public ResponseEntity<Void> deleteArtifact(
        @PathVariable String appName,
        @PathVariable String userId,
        @PathVariable String sessionId,
        @PathVariable String artifactName) {
      log.info(
          "Request received to delete artifact: app={}, user={}, session={}, artifact={}",
          appName,
          userId,
          sessionId,
          artifactName);

      try {

        artifactService.deleteArtifact(appName, userId, sessionId, artifactName);
        log.info("Artifact deleted successfully: {}", artifactName);
        return ResponseEntity.noContent().build();
      } catch (Exception e) {
        log.error("Error deleting artifact {}", artifactName, e);

        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Error deleting artifact", e);
      }
    }

    /**
     * Executes a non-streaming agent run for a given session and message.
     *
     * @param request The AgentRunRequest containing run details.
     * @return A list of events generated during the run.
     * @throws ResponseStatusException if the session is not found or the run fails.
     */
    @PostMapping("/run")
    public List<Event> agentRun(@RequestBody AgentRunRequest request) {
      if (request.appName == null || request.appName.trim().isEmpty()) {
        log.warn("appName cannot be null or empty in POST /run request.");
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "appName cannot be null or empty");
      }
      if (request.sessionId == null || request.sessionId.trim().isEmpty()) {
        log.warn("sessionId cannot be null or empty in POST /run request.");
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "sessionId cannot be null or empty");
      }
      log.info("Request received for POST /run for session: {}", request.sessionId);

      Runner runner = this.runnerService.getRunner(request.appName);
      try {

        RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.NONE).build();
        Flowable<Event> eventStream =
            runner.runAsync(request.userId, request.sessionId, request.newMessage, runConfig);

        List<Event> events = Lists.newArrayList(eventStream.blockingIterable());
        log.info("Agent run for session {} generated {} events.", request.sessionId, events.size());
        return events;
      } catch (Exception e) {
        log.error("Error during agent run for session {}", request.sessionId, e);
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent run failed", e);
      }
    }

    /**
     * Executes an agent run and streams the resulting events using Server-Sent Events (SSE).
     *
     * @param request The AgentRunRequest containing run details.
     * @return A Flux that will stream events to the client.
     */
    @PostMapping(value = "/run_sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentRunSse(@RequestBody AgentRunRequest request) {
      SseEmitter emitter = new SseEmitter();

      if (request.appName == null || request.appName.trim().isEmpty()) {
        log.warn(
            "appName cannot be null or empty in SseEmitter request for appName: {}, session: {}",
            request.appName,
            request.sessionId);
        emitter.completeWithError(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName cannot be null or empty"));
        return emitter;
      }
      if (request.sessionId == null || request.sessionId.trim().isEmpty()) {
        log.warn(
            "sessionId cannot be null or empty in SseEmitter request for appName: {}, session: {}",
            request.appName,
            request.sessionId);
        emitter.completeWithError(
            new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "sessionId cannot be null or empty"));
        return emitter;
      }

      log.info(
          "SseEmitter Request received for POST /run_sse_emitter for session: {}",
          request.sessionId);

      final String sessionId = request.sessionId;
      sseExecutor.execute(
          () -> {
            Runner runner;
            try {
              runner = this.runnerService.getRunner(request.appName);
            } catch (ResponseStatusException e) {
              log.warn(
                  "Setup failed for SseEmitter request for session {}: {}",
                  sessionId,
                  e.getMessage());
              try {
                emitter.completeWithError(e);
              } catch (Exception ex) {
                log.warn(
                    "Error completing emitter after setup failure for session {}: {}",
                    sessionId,
                    ex.getMessage());
              }
              return;
            }

            final RunConfig runConfig =
                RunConfig.builder()
                    .setStreamingMode(
                        request.getStreaming() ? StreamingMode.SSE : StreamingMode.NONE)
                    .build();

            Flowable<Event> eventFlowable =
                runner.runAsync(request.userId, request.sessionId, request.newMessage, runConfig);

            Disposable disposable =
                eventFlowable
                    .observeOn(Schedulers.io())
                    .subscribe(
                        event -> {
                          try {
                            log.debug(
                                "SseEmitter: Sending event {} for session {}",
                                event.id(),
                                sessionId);
                            emitter.send(SseEmitter.event().data(event.toJson()));
                          } catch (IOException e) {
                            log.error(
                                "SseEmitter: IOException sending event for session {}: {}",
                                sessionId,
                                e.getMessage());
                            throw new RuntimeException("Failed to send event", e);
                          } catch (Exception e) {
                            log.error(
                                "SseEmitter: Unexpected error sending event for session {}: {}",
                                sessionId,
                                e.getMessage(),
                                e);
                            throw new RuntimeException("Unexpected error sending event", e);
                          }
                        },
                        error -> {
                          log.error(
                              "SseEmitter: Stream error for session {}: {}",
                              sessionId,
                              error.getMessage(),
                              error);
                          try {
                            emitter.completeWithError(error);
                          } catch (Exception ex) {
                            log.warn(
                                "Error completing emitter after stream error for session {}: {}",
                                sessionId,
                                ex.getMessage());
                          }
                        },
                        () -> {
                          log.debug(
                              "SseEmitter: Stream completed normally for session: {}", sessionId);
                          try {
                            emitter.complete();
                          } catch (Exception ex) {
                            log.warn(
                                "Error completing emitter after normal completion for session {}:"
                                    + " {}",
                                sessionId,
                                ex.getMessage());
                          }
                        });
            emitter.onCompletion(
                () -> {
                  log.debug(
                      "SseEmitter: onCompletion callback for session: {}. Disposing subscription.",
                      sessionId);
                  if (!disposable.isDisposed()) {
                    disposable.dispose();
                  }
                });
            emitter.onTimeout(
                () -> {
                  log.debug(
                      "SseEmitter: onTimeout callback for session: {}. Disposing subscription and"
                          + " completing.",
                      sessionId);
                  if (!disposable.isDisposed()) {
                    disposable.dispose();
                  }
                  emitter.complete();
                });
          });

      log.debug("SseEmitter: Returning emitter for session: {}", sessionId);
      return emitter;
    }

    /**
     * Endpoint to get a graph representation of an event (currently returns a placeholder).
     * Requires Graphviz or similar tooling for full implementation.
     *
     * @param appName Application name.
     * @param userId User ID.
     * @param sessionId Session ID.
     * @param eventId Event ID.
     * @return ResponseEntity containing a GraphResponse with placeholder DOT source.
     * @throws ResponseStatusException if the session or event is not found.
     */
    @GetMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}/events/{eventId}/graph")
    public ResponseEntity<GraphResponse> getEventGraph(
        @PathVariable String appName,
        @PathVariable String userId,
        @PathVariable String sessionId,
        @PathVariable String eventId) {
      log.info(
          "Request received for GET /apps/{}/users/{}/sessions/{}/events/{}/graph",
          appName,
          userId,
          sessionId,
          eventId);

      BaseAgent currentAppAgent;
      try {
        currentAppAgent = agentProvider.loadAgent(appName);
      } catch (java.util.NoSuchElementException e) {
        log.warn("Agent app '{}' not found for graph generation.", appName);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new GraphResponse("Agent app not found: " + appName));
      } catch (IllegalStateException e) {
        log.warn("Agent app '{}' failed to load for graph generation: {}", appName, e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new GraphResponse("Agent app failed to load: " + appName));
      }

      Session session = findSessionOrThrow(appName, userId, sessionId);
      Event event =
          session.events().stream()
              .filter(e -> Objects.equals(e.id(), eventId))
              .findFirst()
              .orElse(null);

      if (event == null) {
        log.warn("Event {} not found in session {}", eventId, sessionId);
        return ResponseEntity.ok(new GraphResponse(null));
      }

      log.debug("Found event {} for graph generation.", eventId);

      List<List<String>> highlightPairs = new ArrayList<>();
      String eventAuthor = event.author();
      List<FunctionCall> functionCalls = event.functionCalls();
      List<FunctionResponse> functionResponses = event.functionResponses();

      if (!functionCalls.isEmpty()) {
        log.debug("Processing {} function calls for highlighting.", functionCalls.size());
        for (FunctionCall fc : functionCalls) {
          Optional<String> toolName = fc.name();
          if (toolName.isPresent() && !toolName.get().isEmpty()) {
            highlightPairs.add(ImmutableList.of(eventAuthor, toolName.get()));
            log.trace("Adding function call highlight: {} -> {}", eventAuthor, toolName.get());
          }
        }
      } else if (!functionResponses.isEmpty()) {
        log.debug("Processing {} function responses for highlighting.", functionResponses.size());
        for (FunctionResponse fr : functionResponses) {
          Optional<String> toolName = fr.name();
          if (toolName.isPresent() && !toolName.get().isEmpty()) {
            highlightPairs.add(ImmutableList.of(toolName.get(), eventAuthor));
            log.trace("Adding function response highlight: {} -> {}", toolName.get(), eventAuthor);
          }
        }
      } else {
        log.debug("Processing simple event, highlighting author: {}", eventAuthor);
        highlightPairs.add(ImmutableList.of(eventAuthor, eventAuthor));
      }

      Optional<String> dotSourceOpt =
          AgentGraphGenerator.getAgentGraphDotSource(currentAppAgent, highlightPairs);

      if (dotSourceOpt.isPresent()) {
        log.debug("Successfully generated graph DOT source for event {}", eventId);
        return ResponseEntity.ok(new GraphResponse(dotSourceOpt.get()));
      } else {
        log.warn(
            "Failed to generate graph DOT source for event {} with agent {}",
            eventId,
            currentAppAgent.name());
        return ResponseEntity.ok(new GraphResponse("Could not generate graph for this event."));
      }
    }

    /** Placeholder for creating an evaluation set. */
    @PostMapping("/apps/{appName}/eval_sets/{evalSetId}")
    public ResponseEntity<Object> createEvalSet(
        @PathVariable String appName, @PathVariable String evalSetId) {
      log.warn("Endpoint /apps/{}/eval_sets/{} (POST) is not implemented", appName, evalSetId);
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
          .body(Collections.singletonMap("message", "Eval set creation not implemented"));
    }

    /** Placeholder for listing evaluation sets. */
    @GetMapping("/apps/{appName}/eval_sets")
    public List<String> listEvalSets(@PathVariable String appName) {
      log.warn("Endpoint /apps/{}/eval_sets (GET) is not implemented", appName);
      return Collections.emptyList();
    }

    /** Placeholder for adding a session to an evaluation set. */
    @PostMapping("/apps/{appName}/eval_sets/{evalSetId}/add-session")
    public ResponseEntity<Object> addSessionToEvalSet(
        @PathVariable String appName,
        @PathVariable String evalSetId,
        @RequestBody AddSessionToEvalSetRequest req) {
      log.warn(
          "Endpoint /apps/{}/eval_sets/{}/add-session is not implemented. Request details:"
              + " evalId={}, sessionId={}, userId={}",
          appName,
          evalSetId,
          req.getEvalId(),
          req.getSessionId(),
          req.getUserId());
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
          .body(Collections.singletonMap("message", "Adding session to eval set not implemented"));
    }

    /** Placeholder for listing evaluations within an evaluation set. */
    @GetMapping("/apps/{appName}/eval_sets/{evalSetId}/evals")
    public List<String> listEvalsInEvalSet(
        @PathVariable String appName, @PathVariable String evalSetId) {
      log.warn("Endpoint /apps/{}/eval_sets/{}/evals is not implemented", appName, evalSetId);
      return Collections.emptyList();
    }

    /** Placeholder for running evaluations. */
    @PostMapping("/apps/{appName}/eval_sets/{evalSetId}/run-eval")
    public List<RunEvalResult> runEval(
        @PathVariable String appName,
        @PathVariable String evalSetId,
        @RequestBody RunEvalRequest req) {
      log.warn(
          "Endpoint /apps/{}/eval_sets/{}/run-eval is not implemented. Request details: evalIds={},"
              + " evalMetrics={}",
          appName,
          evalSetId,
          req.getEvalIds(),
          req.getEvalMetrics());
      return Collections.emptyList();
    }

    /**
     * Gets a specific evaluation result. (STUB - Not Implemented)
     *
     * @param appName The application name.
     * @param evalResultId The evaluation result ID.
     * @return A ResponseEntity indicating the endpoint is not implemented.
     */
    @GetMapping("/apps/{appName}/eval_results/{evalResultId}")
    public ResponseEntity<Object> getEvalResult(
        @PathVariable String appName, @PathVariable String evalResultId) {
      log.warn("Endpoint /apps/{}/eval_results/{} (GET) is not implemented", appName, evalResultId);
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
          .body(Collections.singletonMap("message", "Get evaluation result not implemented"));
    }

    /**
     * Lists all evaluation results for an app. (STUB - Not Implemented)
     *
     * @param appName The application name.
     * @return An empty list, as this endpoint is not implemented.
     */
    @GetMapping("/apps/{appName}/eval_results")
    public List<String> listEvalResults(@PathVariable String appName) {
      log.warn("Endpoint /apps/{}/eval_results (GET) is not implemented", appName);
      return Collections.emptyList();
    }
  }

  /** Configuration class for WebSocket handling. */
  @Configuration
  @EnableWebSocket
  public static class WebSocketConfig implements WebSocketConfigurer {

    private final LiveWebSocketHandler liveWebSocketHandler;

    @Autowired
    public WebSocketConfig(LiveWebSocketHandler liveWebSocketHandler) {
      this.liveWebSocketHandler = liveWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
      registry.addHandler(liveWebSocketHandler, "/run_live").setAllowedOrigins("*");
    }
  }

  /**
   * WebSocket Handler for the /run_live endpoint.
   *
   * <p>Manages bidirectional communication for live agent interactions. Assumes the
   * com.google.adk.runner.Runner class has a method: {@code public Flowable<Event> runLive(Session
   * session, Flowable<LiveRequest> liveRequests, List<String> modalities)}
   */
  @Component
  public static class LiveWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final BaseSessionService sessionService;
    private final RunnerService runnerService;

    @Autowired
    public LiveWebSocketHandler(
        ObjectMapper objectMapper, BaseSessionService sessionService, RunnerService runnerService) {
      this.objectMapper = objectMapper;
      this.sessionService = sessionService;
      this.runnerService = runnerService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) throws Exception {
      URI uri = wsSession.getUri();
      if (uri == null) {
        log.warn("WebSocket session URI is null, cannot establish connection.");
        wsSession.close(CloseStatus.SERVER_ERROR.withReason("Invalid URI"));
        return;
      }
      String path = uri.getPath();
      log.info("WebSocket connection established: {} from {}", wsSession.getId(), uri);

      MultiValueMap<String, String> queryParams =
          UriComponentsBuilder.fromUri(uri).build().getQueryParams();
      String appName = queryParams.getFirst("app_name");
      String userId = queryParams.getFirst("user_id");
      String sessionId = queryParams.getFirst("session_id");

      if (appName == null || appName.trim().isEmpty()) {
        log.warn(
            "WebSocket connection for session {} rejected: app_name query parameter is required and"
                + " cannot be empty. URI: {}",
            wsSession.getId(),
            uri);
        wsSession.close(
            CloseStatus.POLICY_VIOLATION.withReason(
                "app_name query parameter is required and cannot be empty"));
        return;
      }
      if (sessionId == null || sessionId.trim().isEmpty()) {
        log.warn(
            "WebSocket connection for session {} rejected: session_id query parameter is required"
                + " and cannot be empty. URI: {}",
            wsSession.getId(),
            uri);
        wsSession.close(
            CloseStatus.POLICY_VIOLATION.withReason(
                "session_id query parameter is required and cannot be empty"));
        return;
      }

      log.debug(
          "Extracted params for WebSocket session {}: appName={}, userId={}, sessionId={},",
          wsSession.getId(),
          appName,
          userId,
          sessionId);

      RunConfig runConfig =
          RunConfig.builder()
              .setResponseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
              .setStreamingMode(StreamingMode.BIDI)
              .build();

      Session session;
      try {
        session =
            sessionService.getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
        if (session == null) {
          log.warn(
              "Session not found for WebSocket: app={}, user={}, id={}. Closing connection.",
              appName,
              userId,
              sessionId);
          wsSession.close(new CloseStatus(WEBSOCKET_PROTOCOL_ERROR, "Session not found"));
          return;
        }
      } catch (Exception e) {
        log.error(
            "Error retrieving session for WebSocket: app={}, user={}, id={}",
            appName,
            userId,
            sessionId,
            e);
        wsSession.close(CloseStatus.SERVER_ERROR.withReason("Failed to retrieve session"));
        return;
      }

      LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
      wsSession.getAttributes().put(LIVE_REQUEST_QUEUE_ATTR, liveRequestQueue);

      Runner runner;
      try {
        runner = this.runnerService.getRunner(appName);
      } catch (ResponseStatusException e) {
        log.error(
            "Failed to get runner for app {} during WebSocket connection: {}",
            appName,
            e.getMessage());
        wsSession.close(
            CloseStatus.SERVER_ERROR.withReason("Runner unavailable: " + e.getReason()));
        return;
      }

      Flowable<Event> eventStream = runner.runLive(session, liveRequestQueue, runConfig);

      Disposable disposable =
          eventStream
              .subscribeOn(Schedulers.io()) // Offload runner work
              .observeOn(Schedulers.io()) // Send messages on I/O threads
              .subscribe(
                  event -> {
                    try {
                      String jsonEvent = objectMapper.writeValueAsString(event);
                      log.debug(
                          "Sending event via WebSocket session {}: {}",
                          wsSession.getId(),
                          jsonEvent);
                      wsSession.sendMessage(new TextMessage(jsonEvent));
                    } catch (JsonProcessingException e) {
                      log.error(
                          "Error serializing event to JSON for WebSocket session {}",
                          wsSession.getId(),
                          e);
                      // Decide if to close session or just log
                    } catch (IOException e) {
                      log.error(
                          "IOException sending message via WebSocket session {}",
                          wsSession.getId(),
                          e);
                      // This might mean the session is already closed or problematic
                      // Consider closing/disposing here
                      try {
                        wsSession.close(
                            CloseStatus.SERVER_ERROR.withReason("Error sending message"));
                      } catch (IOException closeException) {
                        log.warn(
                            "Failed to close WebSocket connection after send error: {}",
                            closeException.getMessage());
                      }
                    }
                  },
                  error -> {
                    log.error(
                        "Error in run_live stream for WebSocket session {}: {}",
                        wsSession.getId(),
                        error.getMessage(),
                        error);
                    String reason =
                        error.getMessage() != null ? error.getMessage() : "Unknown error";
                    try {
                      wsSession.close(
                          new CloseStatus(
                              WEBSOCKET_INTERNAL_SERVER_ERROR,
                              reason.substring(
                                  0, Math.min(reason.length(), WEBSOCKET_MAX_BYTES_FOR_REASON))));
                    } catch (IOException closeException) {
                      log.warn(
                          "Failed to close WebSocket connection after stream error: {}",
                          closeException.getMessage());
                    }
                  },
                  () -> {
                    log.debug(
                        "run_live stream completed for WebSocket session {}", wsSession.getId());
                    try {
                      wsSession.close(CloseStatus.NORMAL);
                    } catch (IOException closeException) {
                      log.warn(
                          "Failed to close WebSocket connection normally: {}",
                          closeException.getMessage());
                    }
                  });
      wsSession.getAttributes().put(LIVE_SUBSCRIPTION_ATTR, disposable);
      log.debug("Live run started for WebSocket session {}", wsSession.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession wsSession, TextMessage message)
        throws Exception {
      LiveRequestQueue liveRequestQueue =
          (LiveRequestQueue) wsSession.getAttributes().get(LIVE_REQUEST_QUEUE_ATTR);

      if (liveRequestQueue == null) {
        log.warn(
            "Received message on WebSocket session {} but LiveRequestQueue is not available (null)."
                + " Message: {}",
            wsSession.getId(),
            message.getPayload());
        return;
      }

      try {
        String payload = message.getPayload();
        log.debug("Received text message on WebSocket session {}: {}", wsSession.getId(), payload);

        JsonNode rootNode = objectMapper.readTree(payload);
        LiveRequest.Builder liveRequestBuilder = LiveRequest.builder();

        if (rootNode.has("content")) {
          Content content = objectMapper.treeToValue(rootNode.get("content"), Content.class);
          liveRequestBuilder.content(content);
        }

        if (rootNode.has("blob")) {
          JsonNode blobNode = rootNode.get("blob");
          Blob.Builder blobBuilder = Blob.builder();
          if (blobNode.has("displayName")) {
            blobBuilder.displayName(blobNode.get("displayName").asText());
          }
          if (blobNode.has("data")) {
            blobBuilder.data(blobNode.get("data").binaryValue());
          }
          // Handle both mime_type and mimeType. Blob states mimeType but we get mime_type from the
          // frontend.
          String mimeType =
              blobNode.has("mimeType")
                  ? blobNode.get("mimeType").asText()
                  : (blobNode.has("mime_type") ? blobNode.get("mime_type").asText() : null);
          if (mimeType != null) {
            blobBuilder.mimeType(mimeType);
          }
          liveRequestBuilder.blob(blobBuilder.build());
        }
        LiveRequest liveRequest = liveRequestBuilder.build();
        liveRequestQueue.send(liveRequest);
      } catch (JsonProcessingException e) {
        log.error(
            "Error deserializing LiveRequest from WebSocket message for session {}: {}",
            wsSession.getId(),
            message.getPayload(),
            e);
        wsSession.sendMessage(
            new TextMessage(
                "{\"error\":\"Invalid JSON format for LiveRequest\", \"details\":\""
                    + e.getMessage()
                    + "\"}"));
      } catch (Exception e) {
        log.error(
            "Unexpected error processing text message for WebSocket session {}: {}",
            wsSession.getId(),
            message.getPayload(),
            e);
        String reason = e.getMessage() != null ? e.getMessage() : "Error processing message";
        wsSession.close(
            new CloseStatus(
                1011,
                reason.substring(0, Math.min(reason.length(), WEBSOCKET_MAX_BYTES_FOR_REASON))));
      }
    }

    @Override
    public void handleTransportError(WebSocketSession wsSession, Throwable exception)
        throws Exception {
      log.error(
          "WebSocket transport error for session {}: {}",
          wsSession.getId(),
          exception.getMessage(),
          exception);
      // Cleanup resources similar to afterConnectionClosed
      cleanupSession(wsSession);
      if (wsSession.isOpen()) {
        String reason = exception.getMessage() != null ? exception.getMessage() : "Transport error";
        wsSession.close(
            CloseStatus.PROTOCOL_ERROR.withReason(
                reason.substring(0, Math.min(reason.length(), WEBSOCKET_MAX_BYTES_FOR_REASON))));
      }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status)
        throws Exception {
      log.info(
          "WebSocket connection closed: {} with status {}", wsSession.getId(), status.toString());
      cleanupSession(wsSession);
    }

    private void cleanupSession(WebSocketSession wsSession) {
      LiveRequestQueue liveRequestQueue =
          (LiveRequestQueue) wsSession.getAttributes().remove(LIVE_REQUEST_QUEUE_ATTR);
      if (liveRequestQueue != null) {
        liveRequestQueue.close(); // Signal end of input to the runner
        log.debug("Called close() on LiveRequestQueue for session {}", wsSession.getId());
      }

      Disposable disposable = (Disposable) wsSession.getAttributes().remove(LIVE_SUBSCRIPTION_ATTR);
      if (disposable != null && !disposable.isDisposed()) {
        disposable.dispose();
      }
      log.debug("Cleaned up resources for WebSocket session {}", wsSession.getId());
    }
  }

  /**
   * Main entry point for the Spring Boot application.
   *
   * @param args Command line arguments.
   */
  public static void main(String[] args) {
    System.setProperty(
        "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE", String.valueOf(10 * 1024 * 1024));
    SpringApplication.run(AdkWebServer.class, args);
    log.info("AdkWebServer application started successfully.");
  }

  // TODO(vorburger): #later return Closeable, which can stop the server (and resets static)
  public static void start(BaseAgent... agents) {
    // Disable CompiledAgentLoader by setting property to prevent its creation
    System.setProperty("adk.agents.loader", "static");

    // Create Spring Application with custom initializer
    SpringApplication app = new SpringApplication(AdkWebServer.class);
    app.addInitializers(
        new ApplicationContextInitializer<ConfigurableApplicationContext>() {
          @Override
          public void initialize(ConfigurableApplicationContext context) {
            // Register the AgentStaticLoader bean before context refresh
            DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) context.getBeanFactory();
            beanFactory.registerSingleton("agentLoader", new AgentStaticLoader(agents));
          }
        });

    app.run(new String[0]);
  }
}
