package com.google.adk.samples.a2aagent;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** Configuration applied on startup, such as Jackson module registrations. */
@ApplicationScoped
public class StartupConfig {

  void onStart(@Observes StartupEvent ev) {
    // Register globally for Vert.x's internal JSON handling
    DatabindCodec.mapper().registerModule(new JavaTimeModule());
  }
}
