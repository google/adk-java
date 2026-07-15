package com.example;

import com.google.adk.utils.ComponentRegistry;

/**
 * Custom ComponentDieRegistry for the user-defined config agent demo.
 *
 * <p>This registry is used to add custom tools and agents to the ADK Web Server.
 */
public class CustomDieRegistry extends ComponentRegistry {

  /** Singleton instance for easy access */
  public static final CustomDieRegistry INSTANCE = new CustomDieRegistry();

  /** Private constructor to initialize custom components */
  public CustomDieRegistry() {
    super();
    register("tools.roll_die", CustomDieTool.ROLL_DIE_INSTANCE);
    register("tools.check_prime", CustomDieTool.CHECK_PRIME_INSTANCE);
  }
}
