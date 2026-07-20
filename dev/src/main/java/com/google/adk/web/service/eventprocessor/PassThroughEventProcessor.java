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

package com.google.adk.web.service.eventprocessor;

import com.google.adk.events.Event;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Pass-through event processor that sends all events as-is without modification.
 *
 * <p>This is the default processor used when no custom processor is provided. It simply converts
 * each event to JSON and passes it through to the client without any transformation or filtering.
 *
 * <p><b>Use Cases:</b>
 *
 * <ul>
 *   <li>Default behavior for generic SSE endpoints
 *   <li>When you want all events sent to the client
 *   <li>As a base class for simple processors that only need to override specific methods
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 * @see EventProcessor
 */
@Component
public class PassThroughEventProcessor implements EventProcessor {

  /**
   * Processes the event by converting it to JSON and returning it.
   *
   * <p>This implementation simply calls {@link Event#toJson()} and returns the result, ensuring all
   * events are sent to the client without modification.
   *
   * @param event the event to process
   * @param context context map (not used in this implementation)
   * @return Optional containing the event JSON
   */
  @Override
  public Optional<String> processEvent(Event event, Map<String, Object> context) {
    return Optional.of(event.toJson());
  }
}
