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

package com.google.adk.tools.skills;

import static com.google.adk.tools.skills.SkillToolset.createErrorResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.LlmAgent;
import com.google.adk.codeexecutors.BaseCodeExecutor;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.adk.skills.Script;
import com.google.adk.skills.Skill;
import com.google.adk.skills.SkillLoader;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tool to execute scripts from a skill's scripts/ directory. */
final class RunSkillScriptTool extends BaseTool {

  private static final String SKILL_NAME = "skill_name";
  private static final String SCRIPT_PATH = "script_path";
  private static final String ARGS = "args";

  private static final Logger logger = LoggerFactory.getLogger(RunSkillScriptTool.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final SkillLoader skillLoader;
  private final BaseCodeExecutor codeExecutor;
  private final long scriptTimeoutSeconds;

  RunSkillScriptTool(
      SkillLoader skillLoader, @Nullable BaseCodeExecutor codeExecutor, long scriptTimeoutSeconds) {
    super("run_skill_script", "Executes a script from a skill's scripts/ directory.");
    this.skillLoader = skillLoader;
    this.codeExecutor = codeExecutor;
    this.scriptTimeoutSeconds = scriptTimeoutSeconds;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(name())
            .description(description())
            .parameters(
                Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(
                        ImmutableMap.of(
                            SKILL_NAME,
                            Schema.builder()
                                .type(Type.Known.STRING)
                                .description("The name of the skill.")
                                .build(),
                            SCRIPT_PATH,
                            Schema.builder()
                                .type(Type.Known.STRING)
                                .description(
                                    "The relative path to the script (e.g., 'scripts/setup.py').")
                                .build(),
                            ARGS,
                            Schema.builder()
                                .type(Type.Known.OBJECT)
                                .description(
                                    "Optional arguments to pass to the script as key-value pairs.")
                                .build()))
                    .required(ImmutableList.of(SKILL_NAME, SCRIPT_PATH))
                    .build())
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(
      Map<String, Object> toolArgs, ToolContext toolContext) {
    String skillName = (String) toolArgs.get(SKILL_NAME);
    String scriptPath = (String) toolArgs.get(SCRIPT_PATH);

    @SuppressWarnings("unchecked") // Args are passed as a Map by the LLM
    Map<String, Object> scriptArgs =
        (Map<String, Object>) toolArgs.getOrDefault(ARGS, new HashMap<>());

    if (skillName == null || skillName.isEmpty()) {
      return createErrorResponse("Skill name is required.", "MISSING_SKILL_NAME");
    }
    if (scriptPath == null || scriptPath.isEmpty()) {
      return createErrorResponse("Script path is required.", "MISSING_SCRIPT_PATH");
    }

    Skill skill;
    try {
      skill = skillLoader.loadSkill(skillName);
    } catch (Exception e) {
      return createErrorResponse("Skill '" + skillName + "' not found.", "SKILL_NOT_FOUND");
    }

    String scriptName = scriptPath;
    if (scriptPath.startsWith("scripts/")) {
      scriptName = scriptPath.substring("scripts/".length());
    }

    Script script = skill.resources().getScript(scriptName).orElse(null);
    if (script == null) {
      return createErrorResponse(
          "Script '" + scriptPath + "' not found in skill '" + skillName + "'.",
          "SCRIPT_NOT_FOUND");
    }

    // Fallback to toolContext.invocationContext().agent() for a code executor.
    BaseCodeExecutor executor =
        Optional.ofNullable(codeExecutor)
            .or(
                () ->
                    Optional.of(toolContext.invocationContext().agent())
                        .filter(LlmAgent.class::isInstance)
                        .map(LlmAgent.class::cast)
                        .flatMap(LlmAgent::codeExecutor))
            .orElse(null);
    if (executor == null) {
      return createErrorResponse(
          "No code executor configured. A code executor is required to run scripts.",
          "NO_CODE_EXECUTOR");
    }

    String wrapperCode;
    try {
      wrapperCode = buildWrapperCode(skill, scriptPath, scriptArgs);
    } catch (IllegalArgumentException e) {
      return createErrorResponse(
          "Unsupported script type. Supported types: .py, .sh, .bash", "UNSUPPORTED_SCRIPT_TYPE");
    } catch (JsonProcessingException e) {
      return createErrorResponse(
          "Failed to construct execution context: " + e.getMessage(), "EXECUTION_ERROR");
    }

    if (wrapperCode == null) {
      return createErrorResponse(
          "Unsupported script type. Supported types: .py, .sh, .bash", "UNSUPPORTED_SCRIPT_TYPE");
    }

    return Single.fromCallable(
        () -> {
          try {
            CodeExecutionResult result =
                executor.executeCode(
                    toolContext.invocationContext(),
                    CodeExecutionInput.builder().code(wrapperCode).build());

            String stdout = result.stdout() != null ? result.stdout() : "";
            String stderr = result.stderr() != null ? result.stderr() : "";

            // Try parsing stdout as JSON from wrapper
            int rc = 0;
            boolean isShell = scriptPath.endsWith(".sh") || scriptPath.endsWith(".bash");
            if (isShell && !stdout.isEmpty()) {
              try {
                @SuppressWarnings("unchecked") // JSON parse to Map is safe
                Map<String, Object> parsed = OBJECT_MAPPER.readValue(stdout, Map.class);
                if (parsed.get("__shell_result__") == Boolean.TRUE) {
                  stdout = (String) parsed.getOrDefault("stdout", "");
                  stderr = (String) parsed.getOrDefault("stderr", "");
                  if (parsed.get("returncode") instanceof Number) {
                    rc = ((Number) parsed.get("returncode")).intValue();
                  }
                  if (rc != 0 && stderr.isEmpty()) {
                    stderr = "Exit code " + rc;
                  }
                }
              } catch (Exception ignored) {
                // Fallback to raw output
              }
            }

            String status = "success";
            if (rc != 0) {
              status = "error";
            } else if (!stderr.isEmpty() && stdout.isEmpty()) {
              status = "error";
            } else if (!stderr.isEmpty()) {
              status = "warning";
            }

            return ImmutableMap.<String, Object>builder()
                .put(SKILL_NAME, skillName)
                .put(SCRIPT_PATH, scriptPath)
                .put("stdout", stdout)
                .put("stderr", stderr)
                .put("status", status)
                .buildOrThrow();

          } catch (RuntimeException e) {
            logger.error("Error executing script '{}' from skill '{}'", scriptPath, skillName, e);
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            return ImmutableMap.<String, Object>builder()
                .put(
                    "error",
                    "Failed to execute script '"
                        + scriptPath
                        + "':\n"
                        + e.getClass().getSimpleName()
                        + ": "
                        + msg)
                .put("error_code", "EXECUTION_ERROR")
                .buildOrThrow();
          }
        });
  }

  @Nullable
  private String buildWrapperCode(Skill skill, String scriptPath, Map<String, Object> scriptArgs)
      throws JsonProcessingException {
    String ext = "";
    if (scriptPath.contains(".")) {
      ext = scriptPath.substring(scriptPath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    if (!scriptPath.startsWith("scripts/")) {
      scriptPath = "scripts/" + scriptPath;
    }

    // Using object to accommodate byte array serialization
    Map<String, Object> filesDict = getAllSkillResources(skill);

    // Convert to JSON literal for python execution
    String filesJson = OBJECT_MAPPER.writeValueAsString(filesDict);

    List<String> codeLines = new ArrayList<>();
    codeLines.add("import os, tempfile, sys, json as _json, subprocess, runpy, base64");
    codeLines.add(
        "_files_data = _json.loads('''"
            + filesJson.replace("\\", "\\\\").replace("'", "\\'")
            + "''')");
    codeLines.add("def _materialize_and_run():");
    codeLines.add("  _orig_cwd = os.getcwd()");
    codeLines.add("  with tempfile.TemporaryDirectory() as td:");
    codeLines.add("    for rel_path, content in _files_data.items():");
    codeLines.add("      full_path = os.path.join(td, rel_path)");
    codeLines.add("      os.makedirs(os.path.dirname(full_path), exist_ok=True)");
    codeLines.add(
        "      # We only support utf-8 content decoding back from JSON in this simple wrapper.");
    codeLines.add("      mode = 'w'");
    codeLines.add(
        "      # If it was base64 encoded byte array from Jackson, it comes as base64 string.");
    codeLines.add("      # But we will write utf-8.");
    codeLines.add("      with open(full_path, mode, encoding='utf-8') as f:");
    codeLines.add("        f.write(content)");
    codeLines.add("    os.chdir(td)");
    codeLines.add("    try:");

    if (ext.equals("py")) {
      List<String> argvList = new ArrayList<>();
      argvList.add(scriptPath);
      scriptArgs.forEach(
          (k, v) -> {
            argvList.add("--" + k);
            argvList.add(String.valueOf(v));
          });
      String argvJson = OBJECT_MAPPER.writeValueAsString(argvList);
      codeLines.add("      sys.argv = _json.loads('''" + argvJson + "''')");
      codeLines.add("      try:");
      codeLines.add("        runpy.run_path('" + scriptPath + "', run_name='__main__')");
      codeLines.add("      except SystemExit as e:");
      codeLines.add("        if e.code is not None and e.code != 0:");
      codeLines.add("          raise e");
    } else if (ext.equals("sh") || ext.equals("bash")) {
      List<String> arr = new ArrayList<>();
      arr.add("bash");
      arr.add(scriptPath);
      scriptArgs.forEach(
          (k, v) -> {
            arr.add("--" + k);
            arr.add(String.valueOf(v));
          });
      long timeout = this.scriptTimeoutSeconds;
      String arrJson = OBJECT_MAPPER.writeValueAsString(arr);
      codeLines.add("      try:");
      codeLines.add("        _r = subprocess.run(");
      codeLines.add("          _json.loads('''" + arrJson + "'''),");
      codeLines.add("          capture_output=True, text=True,");
      codeLines.add("          timeout=" + timeout + ", cwd=td)");
      codeLines.add("        print(_json.dumps({");
      codeLines.add("            '__shell_result__': True,");
      codeLines.add("            'stdout': _r.stdout,");
      codeLines.add("            'stderr': _r.stderr,");
      codeLines.add("            'returncode': _r.returncode,");
      codeLines.add("        }))");
      codeLines.add("      except subprocess.TimeoutExpired as _e:");
      codeLines.add("        print(_json.dumps({");
      codeLines.add("            '__shell_result__': True,");
      codeLines.add("            'stdout': _e.stdout or '',");
      codeLines.add("            'stderr': 'Timed out after " + timeout + "s',");
      codeLines.add("            'returncode': -1,");
      codeLines.add("        }))");
    } else {
      return null;
    }

    codeLines.add("    finally:");
    codeLines.add("      os.chdir(_orig_cwd)");
    codeLines.add("_materialize_and_run()");

    return String.join("\n", codeLines);
  }

  private static Map<String, Object> getAllSkillResources(Skill skill) {
    Map<String, Object> filesDict = new HashMap<>();
    skill.resources().references().forEach((k, v) -> filesDict.put("references/" + k, v));
    skill.resources().assets().forEach((k, v) -> filesDict.put("assets/" + k, v));
    skill.resources().scripts().forEach((k, v) -> filesDict.put("scripts/" + k, v.src()));
    return filesDict;
  }
}
