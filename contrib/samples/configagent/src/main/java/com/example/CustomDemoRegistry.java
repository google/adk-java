package com.example;

import com.google.adk.utils.ComponentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom ComponentRegistry for the user-defined config agent demo.
 *
 * <p>This registry is used to add custom tools and agents to the ADK Web Server.
 */
public class CustomDemoRegistry extends ComponentRegistry {
  private static final Logger logger = LoggerFactory.getLogger(CustomDemoRegistry.class);

  /** Singleton instance for easy access */
  public static final CustomDemoRegistry INSTANCE = new CustomDemoRegistry();

  /** Private constructor to initialize custom components */
  public CustomDemoRegistry() {
    super();
    // for demo sub_agents_config
    register("sub_agents_config.life_agent.agent", LifeAgent.INSTANCE);

    // for demo tool_functions_config
    register("tool_functions_config.tools.roll_die", CustomDieTool.ROLL_DIE_INSTANCE);
    register("tool_functions_config.tools.check_prime", CustomDieTool.CHECK_PRIME_INSTANCE);

    // Register the tools for core_callback_config
    register("core_callback_config.tools.roll_die", CustomDieTool.ROLL_DIE_INSTANCE);
    register("core_callback_config.tools.check_prime", CustomDieTool.CHECK_PRIME_INSTANCE);
    register(
        "core_callback_config.callbacks.before_agent_callback1",
        CoreCallbacks.BEFORE_AGENT_CALLBACK1);
    register(
        "core_callback_config.callbacks.before_agent_callback2",
        CoreCallbacks.BEFORE_AGENT_CALLBACK2);
    register(
        "core_callback_config.callbacks.before_agent_callback3",
        CoreCallbacks.BEFORE_AGENT_CALLBACK3);

    register(
        "core_callback_config.callbacks.after_agent_callback1",
        CoreCallbacks.AFTER_AGENT_CALLBACK1);
    register(
        "core_callback_config.callbacks.after_agent_callback2",
        CoreCallbacks.AFTER_AGENT_CALLBACK2);
    register(
        "core_callback_config.callbacks.after_agent_callback3",
        CoreCallbacks.AFTER_AGENT_CALLBACK3);

    register(
        "core_callback_config.callbacks.before_model_callback",
        CoreCallbacks.BEFORE_MODEL_CALLBACK);
    register(
        "core_callback_config.callbacks.after_model_callback", CoreCallbacks.AFTER_MODEL_CALLBACK);

    register(
        "core_callback_config.callbacks.before_agent_callback",
        CoreCallbacks.BEFORE_AGENT_CALLBACK);
    register(
        "core_callback_config.callbacks.after_agent_callback", CoreCallbacks.AFTER_AGENT_CALLBACK);

    register(
        "core_callback_config.callbacks.before_tool_callback1",
        CoreCallbacks.BEFORE_TOOL_CALLBACK1);
    register(
        "core_callback_config.callbacks.before_tool_callback2",
        CoreCallbacks.BEFORE_TOOL_CALLBACK2);
    register(
        "core_callback_config.callbacks.before_tool_callback3",
        CoreCallbacks.BEFORE_TOOL_CALLBACK3);

    register(
        "core_callback_config.callbacks.after_tool_callback1", CoreCallbacks.AFTER_TOOL_CALLBACK1);
    register(
        "core_callback_config.callbacks.after_tool_callback2", CoreCallbacks.AFTER_TOOL_CALLBACK2);
    register(
        "core_callback_config.callbacks.after_tool_callback3", CoreCallbacks.AFTER_TOOL_CALLBACK3);

    logger.info("CustomDemoRegistry initialized: callbacks, tools and agents registered.");
  }
}
