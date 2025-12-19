package com.google.adk.tools;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.base.VerifyException;
import com.google.genai.types.FunctionCall;
import io.reactivex.rxjava3.core.Maybe;
import java.util.function.BiFunction;

public interface MissingToolResolutionStrategy {
  public static final MissingToolResolutionStrategy THROW_EXCEPTION =
      (invocationContext, functionCall) -> {
        throw new VerifyException(
            "Tool not found: " + functionCall.name().orElse(functionCall.toJson()));
      };

  public static final MissingToolResolutionStrategy RETURN_ERROR =
      (invocationContext, functionCall) ->
          Maybe.error(
              new VerifyException(
                  "Tool not found: " + functionCall.name().orElse(functionCall.toJson())));

  public static final MissingToolResolutionStrategy IGNORE =
      (invocationContext, functionCall) -> Maybe.empty();

  public static MissingToolResolutionStrategy respondWithEvent(
      BiFunction<InvocationContext, FunctionCall, Maybe<Event>> eventFactory) {
    return eventFactory::apply;
  }

  public static MissingToolResolutionStrategy respondWithEventSync(
      BiFunction<InvocationContext, FunctionCall, Event> eventFactory) {
    return respondWithEvent(
        (invocationContext, functionCall) ->
            Maybe.just(eventFactory.apply(invocationContext, functionCall)));
  }

  Maybe<Event> onMissingTool(InvocationContext invocationContext, FunctionCall functionCall);
}
