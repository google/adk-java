package com.google.adk.a2a.common;

/** Constants and utilities for A2A metadata keys. */
public final class A2AMetadata {

  /** Enum for A2A custom metadata keys. */
  public enum Key {
    REQUEST("a2a:request"),
    RESPONSE("a2a:response"),
    AGGREGATED("a2a:aggregated");

    private final String value;

    Key(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  private A2AMetadata() {}
}
