package com.google.adk.a2a.common;

/** Exception thrown when the A2A client encounters an error. */
public class A2AClientError extends RuntimeException {
  public A2AClientError(String message) {
    super(message);
  }

  public A2AClientError(String message, Throwable cause) {
    super(message, cause);
  }
}
