package com.google.adk.tools;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.base.VerifyException;
import com.google.genai.types.FunctionCall;
import io.reactivex.rxjava3.core.Maybe;
import java.util.function.BiFunction;

public interface MissingToolResolutionStrategy {
  public static final MissingToolResolutionStrategy THROW_EXCEPTION =
      new MissingToolResolutionStrategy() {
        @Override
        public Maybe<Event> onMissingTool(
            InvocationContext invocationContext, FunctionCall functionCall) {
          throw new VerifyException(
              "Tool not found: " + functionCall.name().orElse(functionCall.toJson()));
        }
      };

  public static final MissingToolResolutionStrategy RETURN_ERROR =
      new MissingToolResolutionStrategy() {
        @Override
        public Maybe<Event> onMissingTool(
            InvocationContext invocationContext, FunctionCall functionCall) {
          return Maybe.error(
              new VerifyException(
                  "Tool not found: " + functionCall.name().orElse(functionCall.toJson())));
        }
      };

  public static final MissingToolResolutionStrategy IGNORE =
      new MissingToolResolutionStrategy() {
        @Override
        public Maybe<Event> onMissingTool(
            InvocationContext invocationContext, FunctionCall functionCall) {
          return Maybe.empty();
        }
      };

  public static MissingToolResolutionStrategy respondWithEvent(
      BiFunction<InvocationContext, FunctionCall, Maybe<Event>> eventFactory) {
    return new MissingToolResolutionStrategy() {
      @Override
      public Maybe<Event> onMissingTool(
          InvocationContext invocationContext, FunctionCall functionCall) {
        return eventFactory.apply(invocationContext, functionCall);
      }
    };
  }

  public static MissingToolResolutionStrategy respondWithEventSync(
      BiFunction<InvocationContext, FunctionCall, Event> eventFactory) {
    return respondWithEvent(
        (invocationContext, functionCall) ->
            Maybe.just(eventFactory.apply(invocationContext, functionCall)));
  }

  Maybe<Event> onMissingTool(InvocationContext invocationContext, FunctionCall functionCall);
}
