/*
 * Copyright 2026 Google LLC
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Keeps the bundled Dev UI and the server's routes in step. Nothing else checks the calls the
 * bundle makes, so a refresh that moves an endpoint would otherwise surface only as a 404 in a
 * browser; refreshing it means updating {@link #UI_ENDPOINTS} here.
 */
@SpringBootTest
class DevUiContractTest {

  /** Every call the bundled Dev UI (adk-web 1.0.7) makes. */
  private static final ImmutableList<UiEndpoint> UI_ENDPOINTS =
      ImmutableList.of(
          UiEndpoint.supported("POST", "/run_sse"),
          UiEndpoint.supported("GET", "/list-apps"),
          UiEndpoint.supported("GET", "/version"),
          UiEndpoint.supported("GET", "/apps/{appName}/users/{userId}/sessions"),
          UiEndpoint.supported("POST", "/apps/{appName}/users/{userId}/sessions"),
          UiEndpoint.supported("GET", "/apps/{appName}/users/{userId}/sessions/{sessionId}"),
          UiEndpoint.unsupported(
              "PATCH",
              "/apps/{appName}/users/{userId}/sessions/{sessionId}",
              "renaming a session or editing its state is not implemented"),
          UiEndpoint.supported("DELETE", "/apps/{appName}/users/{userId}/sessions/{sessionId}"),
          UiEndpoint.supported(
              "GET",
              "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}"),
          UiEndpoint.supported(
              "GET",
              "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}"
                  + "/versions/{version}"),
          UiEndpoint.webSocket("/run_live"),
          UiEndpoint.supported("GET", "/config/telemetry"),
          UiEndpoint.unsupported(
              "POST", "/config/telemetry", "telemetry stays off, so there is no consent to store"),
          UiEndpoint.unsupported(
              "POST", "/agent-identity/finalize", "Agent Identity credentials are not supported"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/debug/trace/{eventId}"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/debug/trace/session/{sessionId}"),
          UiEndpoint.unsupported(
              "GET",
              "/dev/apps/{appName}/users/{userId}/sessions/{sessionId}/events/{eventId}/graph",
              "the bundle defines this call but never makes it"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/build_graph"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/build_graph_image"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/builder"),
          UiEndpoint.supported("POST", "/dev/apps/{appName}/builder/save"),
          UiEndpoint.supported("POST", "/dev/apps/{appName}/builder/cancel"),
          UiEndpoint.supported("POST", "/dev/apps/{appName}/eval-sets"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/eval-sets/{evalSetId}"),
          UiEndpoint.supported("DELETE", "/dev/apps/{appName}/eval-sets/{evalSetId}"),
          UiEndpoint.supported("POST", "/dev/apps/{appName}/eval-sets/{evalSetId}/run"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/eval_sets"),
          UiEndpoint.supported("POST", "/dev/apps/{appName}/eval_sets/{evalSetId}/add_session"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/eval_sets/{evalSetId}/evals"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}"),
          UiEndpoint.supported("PUT", "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}"),
          UiEndpoint.supported(
              "DELETE", "/dev/apps/{appName}/eval_sets/{evalSetId}/evals/{evalId}"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/eval_results"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/eval_results/{evalResultId}"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/metrics-info"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/deploy/defaults"),
          UiEndpoint.supported("POST", "/dev/apps/{appName}/deploy/{target}"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/tests"),
          UiEndpoint.supported("POST", "/dev/apps/{appName}/tests/rebuild"),
          UiEndpoint.supported("POST", "/dev/apps/{appName}/tests/run"),
          UiEndpoint.supported("GET", "/dev/apps/{appName}/tests/{testName}"),
          UiEndpoint.supported("PUT", "/dev/apps/{appName}/tests/{testName}"),
          UiEndpoint.supported("DELETE", "/dev/apps/{appName}/tests/{testName}"));

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

  private static final Pattern MAIN_SCRIPT =
      Pattern.compile("src=\\x22(?:\\./)?(main-[\\w-]+\\.js)\\x22");

  private static final Pattern CHUNK_SCRIPT = Pattern.compile("chunk-[\\w-]+\\.js");

  /** What the main bundle builds every API URL from; a chunk that names one calls the API too. */
  private static final ImmutableList<String> API_MARKERS =
      ImmutableList.of("apiServerDomain", "getApiServerBaseUrl", "getWSServerUrl");

  private static final Pattern INTERPOLATION = Pattern.compile("[$]\\{[^{}]*}");

  /** A path inside a URL literal: literal URL characters or {@code ${...}} interpolations. */
  private static final String URL_CHARS = "(?:[\\w\\-./?=&]|[$]\\{[^{}`]*})*";

  /** Group 1 is the literal's opening quote, group 2 the path after the base URL. */
  private static final ImmutableList<CallSite> CALL_SITES =
      ImmutableList.of(
          new CallSite(
              "apiServerDomain interpolated",
              Pattern.compile("(`)[$]\\{[\\w$.]+\\.apiServerDomain}(" + URL_CHARS + ")`"),
              5,
              null),
          new CallSite(
              "apiServerDomain concatenated with a template",
              Pattern.compile("\\.apiServerDomain\\s*\\+\\s*(`)(" + URL_CHARS + ")`"),
              25,
              null),
          new CallSite(
              "apiServerDomain concatenated with a string",
              Pattern.compile("\\.apiServerDomain\\s*\\+\\s*(\\x22)([\\w\\-./?=&]*)\\x22"),
              3,
              null),
          new CallSite(
              "base URL held in a local",
              Pattern.compile(
                  "getApiServerBaseUrl\\(\\)[;,][^`]{0,120}(`)[$]\\{\\w+}(" + URL_CHARS + ")`"),
              3,
              null),
          new CallSite(
              "WebSocket URL",
              Pattern.compile(
                  "(`)[^`]{0,40}[$]\\{[\\w$.]+\\.getWSServerUrl\\(\\)}(" + URL_CHARS + ")`"),
              1,
              "GET"));

  private static final Pattern VERB_BEFORE =
      Pattern.compile("\\.http\\.(get|post|put|patch|delete)\\(\\s*$");

  private static final Pattern VERB_AFTER =
      Pattern.compile("\\.http\\.(get|post|put|patch|delete)\\(|method:\\s*\\x22([A-Z]+)\\x22");

  private static final int VERB_WINDOW_BEFORE = 40;

  /** Wide enough for the assignment and return between a URL and its call, not the next call. */
  private static final int VERB_WINDOW_AFTER = 260;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping requestMappings;

  @Autowired private List<AbstractUrlHandlerMapping> urlMappings;

  @Test
  void bundleCalls_matchTheEndpointList() throws IOException {
    // A list, so an endpoint listed twice fails the comparison instead of collapsing.
    ImmutableList<String> listed =
        UI_ENDPOINTS.stream()
            .map(endpoint -> endpoint.method() + " " + canonical(endpoint.template()))
            .collect(toImmutableList());

    assertWithMessage("API calls in the bundled Dev UI versus UI_ENDPOINTS")
        .that(bundleCalls(readMainBundle()))
        .containsExactlyElementsIn(listed);
  }

  @Test
  void chunks_makeNoApiCalls() throws IOException {
    Map<String, String> chunks = loadableChunks();
    ImmutableList<String> callers =
        chunks.entrySet().stream()
            .filter(chunk -> API_MARKERS.stream().anyMatch(chunk.getValue()::contains))
            .map(Map.Entry::getKey)
            .collect(toImmutableList());

    assertWithMessage("chunk-*.js scripts the UI can load").that(chunks).isNotEmpty();
    assertWithMessage("Chunks that call the API, which bundleCalls does not read")
        .that(callers)
        .isEmpty();
  }

  @Test
  void supportedEndpoints_allRoute() {
    Set<String> routes = routes();

    ImmutableList<String> unrouted =
        UI_ENDPOINTS.stream()
            .filter(endpoint -> endpoint.unsupported() == null)
            .filter(endpoint -> !isRouted(endpoint, routes))
            .map(endpoint -> endpoint.method() + " " + endpoint.template())
            .collect(toImmutableList());

    assertWithMessage("The bundled Dev UI calls these, but the server does not route them")
        .that(unrouted)
        .isEmpty();
  }

  @Test
  void unsupportedEndpoints_stayUnrouted() {
    Set<String> routes = routes();

    ImmutableList<String> nowRouted =
        UI_ENDPOINTS.stream()
            .filter(endpoint -> endpoint.unsupported() != null)
            .filter(endpoint -> isRouted(endpoint, routes))
            .map(endpoint -> endpoint.method() + " " + endpoint.template())
            .collect(toImmutableList());

    assertWithMessage("These now route, so drop their unsupported reason in UI_ENDPOINTS")
        .that(nowRouted)
        .isEmpty();
  }

  /**
   * Returns every route with a handler: "METHOD /path" for a request mapping, with {@code *} for
   * one that takes any method, and "URL /path" for a URL handler, such as the WebSocket endpoint.
   */
  private Set<String> routes() {
    Set<String> routes = new HashSet<>();
    for (RequestMappingInfo info : requestMappings.getHandlerMethods().keySet()) {
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : info.getPatternValues()) {
        if (methods.isEmpty()) {
          routes.add("* " + canonical(pattern));
        }
        for (RequestMethod method : methods) {
          routes.add(method.name() + " " + canonical(pattern));
        }
      }
    }
    for (AbstractUrlHandlerMapping mapping : urlMappings) {
      mapping.getHandlerMap().keySet().forEach(path -> routes.add("URL " + path));
      mapping
          .getPathPatternHandlerMap()
          .keySet()
          .forEach(pattern -> routes.add("URL " + pattern.getPatternString()));
    }
    return routes;
  }

  /** Whether {@code routes} has a handler for {@code endpoint}. A 501 stub counts as one. */
  private static boolean isRouted(UiEndpoint endpoint, Set<String> routes) {
    if (endpoint.webSocket()) {
      return routes.contains("URL " + endpoint.template());
    }
    String path = canonical(endpoint.template());
    return routes.contains(endpoint.method() + " " + path) || routes.contains("* " + path);
  }

  /** The main bundle the server serves; its name carries a content hash, so index.html names it. */
  private String readMainBundle() throws IOException {
    Matcher matcher = MAIN_SCRIPT.matcher(readResource("browser/index.html"));
    List<String> scripts = new ArrayList<>();
    while (matcher.find()) {
      scripts.add(matcher.group(1));
    }
    assertWithMessage("main-*.js scripts in the bundled index.html").that(scripts).hasSize(1);
    return readResource("browser/" + scripts.get(0));
  }

  /**
   * Every chunk the UI can load, by name: those index.html and the main bundle name, transitively.
   */
  private Map<String, String> loadableChunks() throws IOException {
    Map<String, String> chunks = new LinkedHashMap<>();
    Deque<String> pending =
        new ArrayDeque<>(chunkNames(readResource("browser/index.html") + readMainBundle()));
    while (!pending.isEmpty()) {
      String name = pending.removeFirst();
      if (chunks.containsKey(name)) {
        continue;
      }
      String source = readResource("browser/" + name);
      chunks.put(name, source);
      pending.addAll(chunkNames(source));
    }
    return chunks;
  }

  private static Set<String> chunkNames(String source) {
    Set<String> names = new TreeSet<>();
    Matcher matcher = CHUNK_SCRIPT.matcher(source);
    while (matcher.find()) {
      names.add(matcher.group());
    }
    return names;
  }

  private String readResource(String path) throws IOException {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
      assertWithMessage("%s on the test classpath", path).that(in).isNotNull();
      return new String(in.readAllBytes(), UTF_8);
    }
  }

  /**
   * Returns every API call the main bundle makes, as "METHOD /path" with each interpolation reduced
   * to {@code {}}; {@link #chunks_makeNoApiCalls} checks that the chunks make none.
   *
   * <p>The minifier renames locals on every build, so this matches only what survives it: the field
   * the base URL is read from and the path appended to it. A site that matches fewer times than its
   * floor fails, so a bundle that builds URLs differently cannot pass by extracting nothing.
   */
  private static Set<String> bundleCalls(String source) {
    Set<String> calls = new TreeSet<>();
    for (CallSite site : CALL_SITES) {
      Matcher matcher = site.pattern().matcher(source);
      int matches = 0;
      while (matcher.find()) {
        matches++;
        String method = site.fixedMethod() != null ? site.fixedMethod() : verbOf(source, matcher);
        assertWithMessage("HTTP method of the %s call to %s", site.name(), matcher.group(2))
            .that(method)
            .isNotNull();
        calls.add(method + " " + canonical(matcher.group(2)));
      }
      assertWithMessage("matches for the %s call site", site.name())
          .that(matches)
          .isAtLeast(site.minMatches());
    }
    return calls;
  }

  /** The verb of the call whose URL literal {@code match} captured: just before it, or after. */
  private static @Nullable String verbOf(String source, Matcher match) {
    int literalStart = match.start(1);
    Matcher before =
        VERB_BEFORE.matcher(
            source.substring(Math.max(0, literalStart - VERB_WINDOW_BEFORE), literalStart));
    if (before.find()) {
      return before.group(1).toUpperCase(Locale.ROOT);
    }
    Matcher after =
        VERB_AFTER.matcher(
            source.substring(
                match.end(), Math.min(source.length(), match.end() + VERB_WINDOW_AFTER)));
    if (!after.find()) {
      return null;
    }
    String verb = after.group(1) != null ? after.group(1) : after.group(2);
    return verb.toUpperCase(Locale.ROOT);
  }

  private static String canonical(String template) {
    int query = template.indexOf('?');
    String path = query < 0 ? template : template.substring(0, query);
    return PLACEHOLDER.matcher(INTERPOLATION.matcher(path).replaceAll("{}")).replaceAll("{}");
  }

  /** One request the Dev UI makes; {@code unsupported} says why the server does not serve it. */
  private record UiEndpoint(
      String method, String template, @Nullable String unsupported, boolean webSocket) {

    static UiEndpoint supported(String method, String template) {
      return new UiEndpoint(method, template, null, false);
    }

    static UiEndpoint unsupported(String method, String template, String reason) {
      return new UiEndpoint(method, template, reason, false);
    }

    static UiEndpoint webSocket(String template) {
      return new UiEndpoint("GET", template, null, true);
    }
  }

  private record CallSite(
      String name, Pattern pattern, int minMatches, @Nullable String fixedMethod) {}
}
