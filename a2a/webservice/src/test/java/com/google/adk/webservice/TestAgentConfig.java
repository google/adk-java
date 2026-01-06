package com.google.adk.webservice;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class TestAgentConfig {
  @Bean
  public BaseAgent testAgent() {
    return new BaseAgent("test", "test agent", ImmutableList.of(), null, null) {
      @Override
      protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return Flowable.empty();
      }

      @Override
      protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
        return Flowable.empty();
      }
    };
  }
}
