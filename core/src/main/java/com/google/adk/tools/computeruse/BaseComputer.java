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

import com.google.adk.tools.Annotations.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.List;

/**
 * Defines an interface for computer environments.
 *
 * <p>This interface defines the standard methods for controlling computer environments, including
 * web browsers and other interactive systems.
 */
public interface BaseComputer {

  /** Returns the screen size of the environment. */
  Single<int[]> screenSize();

  /** Opens the web browser. */
  Single<ComputerState> openWebBrowser();

  /** Clicks at a specific x, y coordinate on the webpage. */
  Single<ComputerState> clickAt(@Schema(name = "x") int x, @Schema(name = "y") int y);

  /** Hovers at a specific x, y coordinate on the webpage. */
  Single<ComputerState> hoverAt(@Schema(name = "x") int x, @Schema(name = "y") int y);

  /** Types text at a specific x, y coordinate. */
  Single<ComputerState> typeTextAt(
      @Schema(name = "x") int x,
      @Schema(name = "y") int y,
      @Schema(name = "text") String text,
      @Schema(name = "press_enter", optional = true) Boolean pressEnter,
      @Schema(name = "clear_before_typing", optional = true) Boolean clearBeforeTyping);

  /** Scrolls the entire webpage in a direction. */
  Single<ComputerState> scrollDocument(@Schema(name = "direction") String direction);

  /** Scrolls at a specific x, y coordinate by magnitude. */
  Single<ComputerState> scrollAt(
      @Schema(name = "x") int x,
      @Schema(name = "y") int y,
      @Schema(name = "direction") String direction,
      @Schema(name = "magnitude") int magnitude);

  /** Waits for specified duration. */
  Single<ComputerState> wait(@Schema(name = "duration") Duration duration);

  /** Navigates back. */
  Single<ComputerState> goBack();

  /** Navigates forward. */
  Single<ComputerState> goForward();

  /** Jumps to search. */
  Single<ComputerState> search();

  /** Navigates to URL. */
  Single<ComputerState> navigate(@Schema(name = "url") String url);

  /** Presses key combination. */
  Single<ComputerState> keyCombination(@Schema(name = "keys") List<String> keys);

  /** Drag and drop. */
  Single<ComputerState> dragAndDrop(
      @Schema(name = "x") int x,
      @Schema(name = "y") int y,
      @Schema(name = "destination_x") int destinationX,
      @Schema(name = "destination_y") int destinationY);

  /** Returns current state. */
  Single<ComputerState> currentState();

  /** Initialize the computer. */
  Completable initialize();

  /** Cleanup resources. */
  Completable close();

  /** Returns the environment. */
  Single<ComputerEnvironment> environment();
}
