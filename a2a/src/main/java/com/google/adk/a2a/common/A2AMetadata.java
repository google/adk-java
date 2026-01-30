package com.google.adk.a2a.common;

/** Constants and utilities for A2A metadata keys. */
public final class A2AMetadata {

  /** Enum for A2A custom metadata keys. */
  public enum Key {
    REQUEST("request"),
    RESPONSE("response"),
    AGGREGATED("aggregated");

    private final String value;

    Key(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  public static String toA2AMetaKey(Key key) {
    return "a2a:" + key.value;
  }

  private A2AMetadata() {}
}
