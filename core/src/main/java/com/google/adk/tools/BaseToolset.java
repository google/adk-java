package com.google.adk.tools;

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool.ToolConfig;
import com.google.common.base.VerifyException;
import com.google.errorprone.annotations.DoNotCall;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Optional;

/** Base abstract class for toolsets. */
public abstract class BaseToolset implements AutoCloseable {

  /**
   * The configuration class type for this toolset.
   *
   * <p>Subclasses can provide their own configuration type by declaring their own {@code static
   * final CONFIG_TYPE} field. The {@link #getConfigType()} method is designed to correctly retrieve
   * the value from the subclass.
   */
  protected static final Class<?> CONFIG_TYPE = Object.class;

  /**
   * Return all tools in the toolset based on the provided context.
   *
   * @param readonlyContext Context used to filter tools available to the agent.
   * @return A Single emitting a list of tools available under the specified context.
   */
  public abstract Flowable<BaseTool> getTools(ReadonlyContext readonlyContext);

  /**
   * Performs cleanup and releases resources held by the toolset.
   *
   * <p>NOTE: This method is invoked, for example, at the end of an agent server's lifecycle or when
   * the toolset is no longer needed. Implementations should ensure that any open connections,
   * files, or other managed resources are properly released to prevent leaks.
   */
  @Override
  public abstract void close() throws Exception;

  /**
   * Gets the config type for this toolset class, supporting inheritance. Subclasses can override
   * the CONFIG_TYPE field to use their own config class.
   */
  public Class<?> getConfigType() {
    try {
      // First try to get the field from the current class
      return (Class<?>) this.getClass().getDeclaredField("CONFIG_TYPE").get(null);
    } catch (NoSuchFieldException e) {
      // If subclass doesn't declare it, walk up to parent class
      Class<?> superclass = this.getClass().getSuperclass();
      while (superclass != null) {
        try {
          return (Class<?>) superclass.getDeclaredField("CONFIG_TYPE").get(null);
        } catch (NoSuchFieldException ex) {
          superclass = superclass.getSuperclass();
        } catch (IllegalAccessException ex) {
          throw new VerifyException("Cannot access CONFIG_TYPE field", ex);
        }
      }
      throw new IllegalStateException("No CONFIG_TYPE field found in class hierarchy", e);
    } catch (IllegalAccessException e) {
      throw new VerifyException("Cannot access CONFIG_TYPE field", e);
    }
  }

  /**
   * Helper method to be used by implementers that returns true if the given tool is in the provided
   * list of tools of if testing against the given ToolPredicate returns true (otherwise false).
   *
   * @param tool The tool to check.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   * @param readonlyContext The current context.
   * @return true if the tool is selected.
   */
  protected boolean isToolSelected(
      BaseTool tool, Optional<Object> toolFilter, Optional<ReadonlyContext> readonlyContext) {
    if (toolFilter.isEmpty()) {
      return true;
    }
    Object filter = toolFilter.get();
    if (filter instanceof ToolPredicate toolPredicate) {
      return toolPredicate.test(tool, readonlyContext);
    }
    if (filter instanceof List) {
      @SuppressWarnings("unchecked")
      List<String> toolNames = (List<String>) filter;
      return toolNames.contains(tool.name());
    }
    return false;
  }

  /**
   * Creates a toolset instance from a config.
   *
   * <p>Concrete classes implementing BaseToolset should provide their own static fromConfig method.
   * This method is here to guide the structure.
   *
   * @param config The config for the toolset.
   * @param configAbsPath The absolute path to the config file that contains the toolset config.
   * @return The toolset instance.
   * @throws ConfigurationException if the toolset cannot be created from the config.
   */
  @DoNotCall("Always throws com.google.adk.agents.ConfigAgentUtils.ConfigurationException")
  public static BaseToolset fromConfig(ToolConfig config, String configAbsPath)
      throws ConfigurationException {
    throw new ConfigurationException(
        "fromConfig not implemented for " + BaseToolset.class.getSimpleName());
  }
}
