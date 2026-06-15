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

package com.google.adk.tools.computeruse;

import static java.lang.String.format;

import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tool that wraps computer control functions for use with LLMs.
 *
 * <p>This tool automatically normalizes coordinates from a virtual coordinate space (by default
 * 1000x1000) to the actual screen size.
 */
public class ComputerUseTool extends FunctionTool {

  private static final Logger logger = LoggerFactory.getLogger(ComputerUseTool.class);

  private final int[] screenSize;
  private final int[] coordinateSpace;

  public ComputerUseTool(Object instance, Method func, int[] screenSize, int[] virtualScreenSize) {
    super(instance, func, /* isLongRunning= */ false);
    this.screenSize = screenSize;
    this.coordinateSpace = virtualScreenSize;
  }

  private int normalize(Object object, String coordinateName, int index) {
    if (!(object instanceof Number number)) {
      throw new IllegalArgumentException(format("%s coordinate must be numeric", coordinateName));
    }
    double coordinate = number.doubleValue();
    int normalized = (int) (coordinate / coordinateSpace[index] * screenSize[index]);
    // Clamp to screen bounds
    int clamped = Math.max(0, Math.min(normalized, screenSize[index] - 1));
    logger.atDebug().log(
        format(
            "%s: %.2f, normalized %s: %d, screen %s size: %d, coordinate-space %s size: %d, "
                + "clamped %s: %d",
            coordinateName,
            coordinate,
            coordinateName,
            normalized,
            coordinateName,
            screenSize[index],
            coordinateName,
            coordinateSpace[index],
            coordinateName,
            clamped));
    return clamped;
  }

  private int normalizeX(Object xObj) {
    return normalize(xObj, "x", 0);
  }

  private int normalizeY(Object yObj) {
    return normalize(yObj, "y", 1);
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    Map<String, Object> normalizedArgs = new HashMap<>(args);

    if (args.containsKey("x")) {
      normalizedArgs.put("x", normalizeX(args.get("x")));
    }
    if (args.containsKey("y")) {
      normalizedArgs.put("y", normalizeY(args.get("y")));
    }
    if (args.containsKey("destination_x")) {
      normalizedArgs.put("destination_x", normalizeX(args.get("destination_x")));
    }
    if (args.containsKey("destination_y")) {
      normalizedArgs.put("destination_y", normalizeY(args.get("destination_y")));
    }

    return super.runAsync(normalizedArgs, toolContext)
        .map(
            result -> {
              // If the underlying tool method returned a structure containing a "screenshot" field
              // (e.g., a ComputerState object), FunctionTool.runAsync will have converted it to a
              // Map. This post-processing step transforms the byte array "screenshot" field into
              // an "image" map with a mimetype and Base64 encoded data, as expected by some
              // consuming systems.
              if (result.containsKey("screenshot") && result.get("screenshot") instanceof byte[]) {
                byte[] screenshot = (byte[]) result.get("screenshot");
                ImmutableMap<String, Object> imageMap =
                    ImmutableMap.of(
                        "mimetype",
                        "image/png",
                        "data",
                        Base64.getEncoder().encodeToString(screenshot));
                Map<String, Object> finalResult = new HashMap<>(result);
                finalResult.remove("screenshot");
                finalResult.put("image", imageMap);
                return finalResult;
              }
              return result;
            });
  }
}
