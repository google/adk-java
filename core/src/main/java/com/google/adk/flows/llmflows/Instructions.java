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

package com.google.adk.flows.llmflows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@link RequestProcessor} that handles instructions and global instructions for LLM flows. */
public final class Instructions implements RequestProcessor {
  private static final Pattern INSTRUCTION_PATTERN = Pattern.compile("\\{+[^\\{\\}]*\\}+");

  public Instructions() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent)) {
      throw new IllegalArgumentException("Agent in InvocationContext is not an instance of Agent.");
    }
    LlmAgent agent = (LlmAgent) context.agent();

    LlmRequest.Builder builder = request.toBuilder();
    if (agent.rootAgent() instanceof LlmAgent) {
      LlmAgent rootAgent = (LlmAgent) agent.rootAgent();

      if (rootAgent.globalInstruction().isPresent()) {
        builder.appendInstructions(
            ImmutableList.of(buildSystemInstruction(context, rootAgent.globalInstruction().get())));
      }
    }

    if (agent.instruction().isPresent()) {
      builder.appendInstructions(
          ImmutableList.of(buildSystemInstruction(context, agent.instruction().get())));
    }

    return Single.just(
        RequestProcessor.RequestProcessingResult.create(builder.build(), ImmutableList.of()));
  }

  private String buildSystemInstruction(InvocationContext context, String instructionTemplate) {
    StringBuffer builder = new StringBuffer();
    Matcher matcher = INSTRUCTION_PATTERN.matcher(instructionTemplate);

    while (matcher.find()) {
      String placeholder = matcher.group();
      String varName = matcher.group();
      varName = varName.replaceAll("^\\{+", "").replaceAll("\\}+$", "").trim();

      boolean optional = false;
      if (varName.endsWith("?")) {
        optional = true;
        varName = varName.substring(0, varName.length() - 1);
      }

      if (varName.startsWith("artifact.")) {
        varName = varName.substring("artifact.".length());
        Session session = context.session();
        Part artifact =
            context
                .artifactService()
                .loadArtifact(
                    session.appName(), session.userId(), session.id(), varName, Optional.empty())
                .blockingGet(null);
        if (artifact == null) {
          throw new IllegalArgumentException(String.format("Artifact %s not found.", varName));
        }
        matcher.appendReplacement(builder, Matcher.quoteReplacement(artifact.toString()));
      } else if (!isValidStateName(varName)) {
        matcher.appendReplacement(builder, Matcher.quoteReplacement(placeholder));
      } else if (context.session().state().containsKey(varName)) {
        Object value = context.session().state().get(varName);
        matcher.appendReplacement(builder, Matcher.quoteReplacement(String.valueOf(value)));
      } else if (optional) {
        matcher.appendReplacement(builder, "");
      } else {
        throw new IllegalArgumentException(
            String.format("Context variable not found: `%s`.", varName));
      }
    }
    matcher.appendTail(builder);
    return builder.toString();
  }

  private boolean isValidStateName(String varName) {
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

  private boolean isValidIdentifier(String s) {
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
