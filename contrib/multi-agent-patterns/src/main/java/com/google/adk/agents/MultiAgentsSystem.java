package com.google.adk.agents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// mvn compile exec:java -Dexec.mainClass=com.google.adk.agents.MultiAgentsSystem

@SpringBootApplication
public class MultiAgentsSystem {

  public static void main(String[] agrs) {
    SpringApplication.run(MultiAgentsSystem.class, agrs);
  }
}
