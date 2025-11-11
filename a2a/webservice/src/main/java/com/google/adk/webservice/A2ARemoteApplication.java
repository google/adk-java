package com.google.adk.webservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Entry point for the standalone Spring Boot A2A service. */
@SpringBootApplication
@Import(A2ARemoteConfiguration.class)
public class A2ARemoteApplication {

  public static void main(String[] args) {
    SpringApplication.run(A2ARemoteApplication.class, args);
  }
}
