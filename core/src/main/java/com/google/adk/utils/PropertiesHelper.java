package com.google.adk.utils;

/*
 * @author Arun Parmar
 * @date 2025-10-28
 * @description PropertiesHelper is a class that loads properties from a file.
 * Supports both file system paths and classpath resources for library usage.
 * @version 2.0.0
 * @since 1.0.0
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import org.ini4j.Wini;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PropertiesHelper {
  private static final Logger logger = LoggerFactory.getLogger(PropertiesHelper.class);
  private static PropertiesHelper propertiesHelper = null;
  private Wini properties = null;
  private final String filePath;
  private final String env;

  private PropertiesHelper(String filePath, String environment) {
    this.filePath = filePath;
    this.env = environment;
    initPool();
  }

  /**
   * Load properties from a file path or classpath resource. This method tries to load from
   * classpath first (for JAR/library usage), then falls back to filesystem path (for standalone
   * usage).
   *
   * @param filePath Path to the config file (can be relative, absolute, or classpath resource)
   * @param environment Environment section name in the INI file (e.g., "production", "development")
   * @return PropertiesHelper instance
   */
  public static PropertiesHelper loadProperties(String filePath, String environment) {
    if (propertiesHelper == null) {
      propertiesHelper = new PropertiesHelper(filePath, environment);
    }
    return propertiesHelper;
  }

  /**
   * Get the singleton instance. If not initialized, attempts to load from default locations or
   * throws an error.
   *
   * @return PropertiesHelper instance
   * @throws Error if properties have not been loaded
   */
  public static PropertiesHelper getInstance() {
    if (propertiesHelper == null) {
      // Try to load from common default locations
      String[] defaultPaths = {
        "config.ini", "core/config.ini", "/config.ini", "src/main/resources/config.ini"
      };
      for (String defaultPath : defaultPaths) {
        try {
          propertiesHelper = new PropertiesHelper(defaultPath, "production");
          if (propertiesHelper.properties != null) {
            logger.info("Auto-loaded properties from: {}", defaultPath);
            return propertiesHelper;
          }
        } catch (Exception e) {
          // Continue to next path
        }
      }
      throw new Error(
          "PropertiesHelper not initialized! Please call PropertiesHelper.loadProperties() first, "
              + "or place config.ini in classpath or working directory.");
    }
    return propertiesHelper;
  }

  /** Initialize the properties pool. Tries to load from classpath first, then filesystem. */
  private void initPool() {
    // First, try loading from classpath (for JAR/library usage)
    InputStream classpathStream = null;
    try {
      // Remove leading slash for classpath resource
      String resourcePath =
          this.filePath.startsWith("/") ? this.filePath.substring(1) : this.filePath;
      classpathStream = getClass().getClassLoader().getResourceAsStream(resourcePath);

      if (classpathStream != null) {
        properties = new Wini(classpathStream);
        logger.info("Loaded properties from classpath resource: {}", resourcePath);
        return;
      }
    } catch (IOException ex) {
      logger.debug("Failed to load from classpath: {}", ex.getMessage());
    } finally {
      if (classpathStream != null) {
        try {
          classpathStream.close();
        } catch (IOException e) {
          // Ignore
        }
      }
    }

    // Fall back to filesystem path (for standalone usage)
    try {
      File configFile = new File(this.filePath);
      if (configFile.exists() && configFile.isFile()) {
        properties = new Wini(configFile);
        logger.info("Loaded properties from filesystem: {}", this.filePath);
        return;
      }
    } catch (IOException ex) {
      logger.debug("Failed to load from filesystem: {}", ex.getMessage());
    }

    // If both failed, create an empty Wini object to avoid NullPointerException
    try {
      properties = new Wini();
      logger.warn(
          "Properties file not found at: {}. PropertiesHelper will use environment variables as fallback.",
          this.filePath);
    } catch (Exception ex) {
      logger.error("Failed to create empty properties object: {}", ex.getMessage());
      properties = null; // Will fall back to environment variables only
    }
  }

  /**
   * Get a property value. Checks INI file first, then falls back to environment variables, then
   * system properties.
   *
   * @param propKey Property key to retrieve
   * @return Property value, or null if not found
   */
  public String getValue(String propKey) {
    // First, try to get from INI file
    if (properties != null) {
      try {
        String value = properties.get(env, propKey);
        if (value != null && !value.trim().isEmpty()) {
          return value;
        }
      } catch (Exception e) {
        logger.debug("Property '{}' not found in INI file: {}", propKey, e.getMessage());
      }
    }

    // Fall back to environment variables (uppercase, with underscore separators)
    String envKey = propKey.toUpperCase().replace(".", "_");
    String envValue = System.getenv(envKey);
    if (envValue != null && !envValue.trim().isEmpty()) {
      logger.debug("Property '{}' found in environment variable: {}", propKey, envKey);
      return envValue;
    }

    // Fall back to system properties
    String sysValue = System.getProperty(propKey);
    if (sysValue != null && !sysValue.trim().isEmpty()) {
      logger.debug("Property '{}' found in system properties", propKey);
      return sysValue;
    }

    // Also try common environment variable patterns
    String[] envPatterns = {
      propKey, // original key
      propKey.toUpperCase(), // uppercase
      propKey.replace(".", "_"), // dot to underscore
      propKey.replace(".", "_").toUpperCase() // dot to underscore + uppercase
    };

    for (String pattern : envPatterns) {
      envValue = System.getenv(pattern);
      if (envValue != null && !envValue.trim().isEmpty()) {
        logger.debug("Property '{}' found in environment variable: {}", propKey, pattern);
        return envValue;
      }
    }

    logger.debug(
        "Property '{}' not found in INI file, environment variables, or system properties",
        propKey);
    return null;
  }

  /**
   * Get a property value with a default value if not found.
   *
   * @param propKey Property key to retrieve
   * @param defaultValue Default value to return if property is not found
   * @return Property value, or defaultValue if not found
   */
  public String getValue(String propKey, String defaultValue) {
    String value = getValue(propKey);
    return value != null ? value : defaultValue;
  }
}
