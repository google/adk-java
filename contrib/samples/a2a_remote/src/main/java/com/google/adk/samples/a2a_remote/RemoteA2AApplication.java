package com.google.adk.samples.a2a_remote;

import com.google.adk.agents.BaseAgent;
import com.google.adk.samples.a2a_remote.remote_prime_agent.Agent;
import com.google.adk.webservice.A2ARemoteConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Spring Boot entry point that wires the shared A2A webservice with the prime demo agent. */
@SpringBootApplication
@Import(A2ARemoteConfiguration.class)
public class RemoteA2AApplication {

  public static void main(String[] args) {
    SpringApplication.run(RemoteA2AApplication.class, args);
  }

  @Bean
  public BaseAgent primeAgent() {
    return Agent.ROOT_AGENT;
  }
}
