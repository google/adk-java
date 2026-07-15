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

package com.google.adk.utils;

import com.google.adk.agents.InvocationContext;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utility methods for handling instruction templates. */
public final class InstructionUtils {

  private static final Pattern INSTRUCTION_PLACEHOLDER_PATTERN =
      Pattern.compile("\\{+[^\\{\\}]*\\}+");

  private InstructionUtils() {}

  /**
   * Populates placeholders in an instruction template string with values from the session state or
   * loaded artifacts.
   *
   * <p><b>Placeholder Syntax:</b>
   *
   * <p>Placeholders are enclosed by one or more curly braces at the start and end, e.g., {@code
   * {key}} or {@code {{key}}}. The core {@code key} is extracted from whatever is between the
   * innermost pair of braces after trimming whitespace and possibly removing the {@code ?} which
   * denotes optionality (e.g. {@code {key?}}). The {@code key} itself must not contain curly
   * braces. For typical usage, a single pair of braces like {@code {my_variable}} is standard.
   *
   * <p>The extracted {@code key} determines the source and name of the value:
   *
   * <ul>
   *   <li><b>Session State Variables:</b> The {@code key} (e.g., {@code "variable_name"} or {@code
   *       "prefix:variable_name"}) refers to a variable in session state.
   *       <ul>
   *         <li>Simple name: {@code {variable_name}}. The {@code variable_name} part must be a
   *             valid identifier as per {@link #isValidStateName(String)}. Invalid names will
   *             result in the placeholder being returned as is.
   *         <li>Prefixed name: {@code {prefix:variable_name}}. Valid prefixes are: {@value
   *             com.google.adk.sessions.State#APP_PREFIX}, {@value
   *             com.google.adk.sessions.State#USER_PREFIX}, and {@value
   *             com.google.adk.sessions.State#TEMP_PREFIX} The part of the name following the
   *             prefix must also be a valid identifier. Invalid prefixes will result in the
   *             placeholder being returned as is.
   *       </ul>
   *   <li><b>Artifacts:</b> The {@code key} starts with "{@code artifact.}" (e.g., {@code
   *       "artifact.file_name"}).
   *   <li><b>Optional Placeholders:</b> A {@code key} can be marked as optional by appending a
   *       question mark {@code ?} at its very end, inside the braces.
   *       <ul>
   *         <li>Example: {@code {optional_variable?}}, {@code {{artifact.optional_file.txt?}}}
   *         <li>If an optional placeholder cannot be resolved (e.g., variable not found, artifact
   *             not found), it is replaced with an empty string.
   *       </ul>
   * </ul>
   *
   * <b>Example Usage:</b>
   *
   * <pre>{@code
   * InvocationContext context = ...; // Assume this is initialized with session and artifact service
   * Session session = context.session();
   *
   * session.state().put("user:name", "Alice");
   *
   * context.artifactService().saveArtifact(
   *     session.appName(), session.userId(), session.id(), "knowledge.txt", Part.fromText("Origins of the universe: At first, there was-"));
   *
   * String template = "You are {user:name}'s assistant. Answer questions based on your knowledge. Your knowledge: {artifact.knowledge.txt}." +
   *                   " Your extra knowledge: {artifact.missing_artifact.txt?}";
   *
   * Single<String> populatedStringSingle = InstructionUtils.injectSessionState(context, template);
   * populatedStringSingle.subscribe(
   *     result -> System.out.println(result),
   *     // Expected: "You are Alice's assistant. Answer questions based on your knowledge. Your knowledge: Origins of the universe: At first, there was-. Your extra knowledge: "
   *     error -> System.err.println("Error populating template: " + error.getMessage())
   * );
   * }</pre>
   *
   * @param context The invocation context providing access to session state and artifact services.
   * @param template The instruction template string containing placeholders to be populated.
   * @return A {@link Single} that will emit the populated instruction string upon successful
   *     resolution of all non-optional placeholders. Emits the original template if it is empty or
   *     contains no placeholders that are processed.
   * @throws NullPointerException if the template or context is null.
   * @throws IllegalArgumentException if a non-optional variable or artifact is not found.
   */
  public static Single<String> injectSessionState(InvocationContext context, String template) {
    if (template == null) {
      return Single.error(new NullPointerException("template cannot be null"));
    }
    if (context == null) {
      return Single.error(new NullPointerException("context cannot be null"));
    }
    Matcher matcher = INSTRUCTION_PLACEHOLDER_PATTERN.matcher(template);
    List<Single<String>> parts = new ArrayList<>();
    int lastEnd = 0;

    while (matcher.find()) {
      if (matcher.start() > lastEnd) {
        parts.add(Single.just(template.substring(lastEnd, matcher.start())));
      }
      MatchResult matchResult = matcher.toMatchResult();
      parts.add(resolveMatchAsync(context, matchResult));
      lastEnd = matcher.end();
    }
    if (lastEnd < template.length()) {
      parts.add(Single.just(template.substring(lastEnd)));
    }

    if (parts.isEmpty()) {
      return Single.just(template);
    }

    return Single.zip(
        parts,
        objects -> {
          StringBuilder sb = new StringBuilder();
          for (Object obj : objects) {
            sb.append(obj);
          }
          return sb.toString();
        });
  }

  private static Single<String> resolveMatchAsync(InvocationContext context, MatchResult match) {
    String placeholder = match.group();
    String varNameFromPlaceholder =
        placeholder.replaceAll("^\\{+", "").replaceAll("\\}+$", "").trim();

    final boolean optional;
    final String cleanVarName;
    if (varNameFromPlaceholder.endsWith("?")) {
      optional = true;
      cleanVarName = varNameFromPlaceholder.substring(0, varNameFromPlaceholder.length() - 1);
    } else {
      optional = false;
      cleanVarName = varNameFromPlaceholder;
    }

    if (cleanVarName.startsWith("artifact.")) {
      final String artifactName = cleanVarName.substring("artifact.".length());
      Session session = context.session();

      Maybe<Part> artifactMaybe =
          context
              .artifactService()
              .loadArtifact(session.appName(), session.userId(), session.id(), artifactName);

      return artifactMaybe
          .map(Part::toJson)
          .switchIfEmpty(
              Single.defer(
                  () -> {
                    if (optional) {
                      return Single.just("");
                    } else {
                      return Single.error(
                          new IllegalArgumentException(
                              String.format("Artifact %s not found.", artifactName)));
                    }
                  }));

    } else if (!isValidStateName(cleanVarName)) {
      return Single.just(placeholder);
    } else if (context.session().state().containsKey(cleanVarName)) {
      Object value = context.session().state().get(cleanVarName);
      return Single.just(String.valueOf(value));
    } else if (optional) {
      return Single.just("");
    } else {
      return Single.error(
          new IllegalArgumentException(
              String.format("Context variable not found: `%s`.", cleanVarName)));
    }
  }

  /**
   * Checks if a given string is a valid state variable name.
   *
   * <p>A valid state variable name must either:
   *
   * <ul>
   *   <li>Be a valid identifier (as defined by {@link Character#isJavaIdentifierStart(int)} and
   *       {@link Character#isJavaIdentifierPart(int)}).
   *   <li>Start with a valid prefix ({@value com.google.adk.sessions.State#APP_PREFIX}, {@value
   *       com.google.adk.sessions.State#USER_PREFIX}, or {@value
   *       com.google.adk.sessions.State#TEMP_PREFIX}) followed by a valid identifier.
   * </ul>
   *
   * @param varName The string to check.
   * @return True if the string is a valid state variable name, false otherwise.
   */
  private static boolean isValidStateName(String varName) {
    if (varName.isEmpty()) {
      return false;
    }
    String[] parts = varName.split(":", 2);
    if (parts.length == 1) {
      return isValidIdentifier(parts[0]);
    }

    if (parts.length == 2) {
      String prefixPart = parts[0] + ":";
      ImmutableSet<String> validPrefixes =
          ImmutableSet.of(State.APP_PREFIX, State.USER_PREFIX, State.TEMP_PREFIX);
      if (validPrefixes.contains(prefixPart)) {
        return isValidIdentifier(parts[1]);
      }
    }
    return false;
  }

  private static boolean isValidIdentifier(String s) {
    if (s.isEmpty()) {
      return false;
    }
    if (!Character.isJavaIdentifierStart(s.charAt(0))) {
      return false;
    }
    for (int i = 1; i < s.length(); i++) {
      if (!Character.isJavaIdentifierPart(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
