package com.redbus.adk.examples.hybridadkagentsystem;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.OllamaBaseLM; // Assuming this is your Ollama model for Java ADK
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Java ADK agent that proxies user input to a Python agent running at http://127.0.0.1:8000/ and
 * returns the response.
 *
 * <p>This agent acts as a client to an external ADK Python agent service.
 *
 * @author Sandeep Belgavi
 * @version 1.0
 * @since 2025-07-04
 */
public class PythonProxyAgent {

  public static String NAME = "product_proxy_agent"; // Renamed agent name for consistency
  private static final String PYTHON_AGENT_RUN_URL = "http://127.0.0.1:8000/run";
  // Format for creating sessions on the Python agent
  private static final String PYTHON_SESSION_CREATE_URL_FORMAT =
      "http://127.0.0.1:8000/apps/%s/users/%s/sessions/%s";

  // Required for ADK UI discovery and runtime execution
  public static BaseAgent ROOT_AGENT = initAgent();

  public static BaseAgent initAgent() {
    return LlmAgent.builder()
        .name(NAME)
        .model(
            new OllamaBaseLM(
                "llama3.2:latest")) // Using a placeholder Java model, LLM calls are proxied.
        // This model mostly serves as a "shell" for the proxy agent.
        .description(
            "Agent that proxies user input to a Python-based product information agent service running at http://127.0.0.1:8000/run and returns the response. "
                + "Leverages a Python backend for specialized tools and LLM capabilities.")
        .instruction(
            "When the user asks a question, call the 'callPythonAgent' tool with the user's message. "
                + "Return the response from the Python agent directly to the user. "
                + "If there is an error, inform the user about the issue and suggest trying again later.")
        .tools(FunctionTool.create(PythonProxyAgent.class, "callPythonAgent"))
        .build();
  }

  /**
   * Calls the Python agent at http://127.0.0.1:8000/run with the user's input. Automatically
   * attempts to create a session if a "Session not found" error occurs.
   *
   * @param userInput The user's message to send to the Python agent.
   * @return A map with status ("success" or "error") and either "result" (the agent's text
   *     response) or "error" message.
   */
  public static Map<String, Object> callPythonAgent(
      @Schema(description = "The user's message to send to the Python product agent.")
          String userInput) {
    // These IDs can be dynamically generated or passed down from a higher-level system
    // For this example, they are hardcoded for simplicity.
    String sessionId = "java_prod_session_001"; // Unique ID for this session
    String userId = "java_prod_user_001"; // Unique ID for the user
    String appName =
        "Product_Agent"; // Matches the `name` of the LlmAgent in python_product_agent_service.py

    // First attempt to call the Python agent
    Map<String, Object> result = sendToPythonAgent(userInput, sessionId, userId, appName);

    // If the first attempt failed due to "Session not found", try creating a session and
    // re-attempting
    if ("error".equals(result.get("status"))
        && result.get("error").toString().contains("Session not found")) {
      System.out.println(
          "Session not found on Python agent. Attempting to create a new session...");
      if (createSession(sessionId, userId, appName)) {
        System.out.println("Session created successfully. Retrying the original request...");
        result =
            sendToPythonAgent(userInput, sessionId, userId, appName); // Retry the original request
      } else {
        return Map.of(
            "status",
            "error",
            "error",
            "Failed to create session with Python agent. Please check the Python agent's logs and connectivity.");
      }
    }
    return result;
  }

  /** Helper method to send the request to the Python agent's /run endpoint. */
  private static Map<String, Object> sendToPythonAgent(
      String userInput, String sessionId, String userId, String appName) {
    System.out.println(
        String.format(
            "Sending message to Python product agent: '%s' for session '%s'",
            userInput, sessionId));
    try {
      URL url = new URL(PYTHON_AGENT_RUN_URL);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setRequestProperty("Accept", "application/json");
      conn.setDoOutput(true);

      // Set reasonable timeouts for network communication
      conn.setConnectTimeout(10000); // 10 seconds to establish connection
      conn.setReadTimeout(60000); // 60 seconds to read response (LLM calls can take time)

      // Build the JSON body according to the ADK API specification for /run endpoint
      JSONObject newMessage = new JSONObject();
      newMessage.put("role", "user");
      newMessage.put("parts", new JSONArray().put(new JSONObject().put("text", userInput)));

      JSONObject payload = new JSONObject();
      payload.put("app_name", appName);
      payload.put("user_id", userId);
      payload.put("session_id", sessionId);
      payload.put("new_message", newMessage);

      // Send the request payload
      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = payload.toString().getBytes("UTF-8");
        os.write(input, 0, input.length);
        os.flush();
      }

      int status = conn.getResponseCode();
      StringBuilder response = new StringBuilder();

      // Read the response from the connection's input or error stream
      try (BufferedReader in =
          new BufferedReader(
              new InputStreamReader(
                  status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream(),
                  "UTF-8"))) {
        String line;
        while ((line = in.readLine()) != null) {
          response.append(line);
        }
      }

      String responseStr = response.toString();
      System.out.println(
          "Raw response from Python product agent (HTTP " + status + "): " + responseStr);

      // Handle specific HTTP status codes
      if (status == 404) {
        // Check if the 404 specifically indicates "Session not found"
        if (responseStr.contains("Session not found")) {
          return Map.of(
              "status",
              "error",
              "error",
              "Session not found. Please create a session first via the /apps/{app_name}/users/{user_id}/sessions/{session_id} endpoint.");
        }
        // Other 404s can be generic errors
        return Map.of(
            "status",
            "error",
            "error",
            "Python product agent endpoint not found (HTTP 404): " + responseStr);
      } else if (status >= 200 && status < 300) {
        // Successful response, parse the ADK protocol events
        if (responseStr.trim().isEmpty()) {
          return Map.of(
              "status", "error", "error", "Empty successful response from Python product agent");
        }

        try {
          // The Python ADK agent returns a JSON array of events
          JSONArray events = new JSONArray(responseStr);
          String lastText = null;

          // Iterate backwards to find the last model's text response.
          // This is robust as the agent might send tool_code events before the final text.
          for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject event = events.getJSONObject(i);

            // Look for 'content' in the event
            if (event.has("content")) {
              JSONObject content = event.getJSONObject("content");
              String role = content.optString("role", "");

              // Check if it's a model response and has text parts
              if ("model".equals(role) && content.has("parts")) {
                JSONArray parts = content.getJSONArray("parts");
                for (int j = 0; j < parts.length(); j++) {
                  JSONObject part = parts.getJSONObject(j);
                  if (part.has("text")) {
                    lastText = part.getString("text");
                    break; // Found the text in this part
                  }
                }
                if (lastText != null) break; // Found the last text, exit the events loop
              }
            }
          }

          if (lastText != null && !lastText.trim().isEmpty()) {
            System.out.println("Extracted text from Python product agent response: " + lastText);
            return Map.of("status", "success", "result", lastText);
          } else {
            System.err.println(
                "No user-facing text found in the response from the Python product agent. Raw response: "
                    + responseStr);
            return Map.of(
                "status",
                "error",
                "error",
                "Python product agent responded, but no relevant text was found. Raw: "
                    + responseStr);
          }
        } catch (Exception jsonEx) {
          System.err.println(
              "Failed to parse JSON response from Python product agent: "
                  + jsonEx.getMessage()
                  + ". Raw response: "
                  + responseStr);
          return Map.of(
              "status",
              "error",
              "error",
              "Failed to parse Python product agent's response: " + jsonEx.getMessage());
        }
      } else {
        // Generic HTTP error
        System.err.println(
            "Python product agent returned HTTP error " + status + ": " + responseStr);
        return Map.of(
            "status",
            "error",
            "error",
            "Python product agent HTTP error " + status + ": " + responseStr);
      }
    } catch (java.net.ConnectException e) {
      System.err.println(
          "Connection refused. Is the Python product agent service running at "
              + PYTHON_AGENT_RUN_URL
              + "? "
              + e.getMessage());
      return Map.of(
          "status",
          "error",
          "error",
          "Connection to Python product agent refused. Ensure the service is running at "
              + PYTHON_AGENT_RUN_URL
              + ".");
    } catch (java.net.SocketTimeoutException e) {
      System.err.println("Request timed out to Python product agent: " + e.getMessage());
      return Map.of(
          "status",
          "error",
          "error",
          "Request to Python product agent timed out. The agent might be slow or unresponsive.");
    } catch (Exception e) {
      System.err.println(
          "Unexpected error communicating with Python product agent: " + e.getMessage());
      e.printStackTrace(); // Log the full stack trace for debugging
      return Map.of("status", "error", "error", "An unexpected error occurred: " + e.getMessage());
    }
  }

  /**
   * Attempts to create a session with the Python agent using its session creation endpoint. This is
   * called when a "Session not found" error is encountered during agent execution.
   *
   * @return true if the session was created successfully (HTTP 2xx), false otherwise.
   */
  private static boolean createSession(String sessionId, String userId, String appName) {
    String sessionCreateUrl =
        String.format(PYTHON_SESSION_CREATE_URL_FORMAT, appName, userId, sessionId);
    System.out.println("Attempting to create session via: " + sessionCreateUrl);
    try {
      URL url = new URL(sessionCreateUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      conn.setDoOutput(true);

      // Payload for session creation (as per ADK's protocol)
      JSONObject payload = new JSONObject();
      payload.put("state", new JSONObject()); // Typically an empty JSON object for initial state

      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = payload.toString().getBytes("UTF-8");
        os.write(input, 0, input.length);
        os.flush();
      }

      int status = conn.getResponseCode();
      if (status >= 200 && status < 300) {
        System.out.println("Successfully created session: " + sessionId);
        return true;
      } else {
        StringBuilder errorResponse = new StringBuilder();
        try (BufferedReader in =
            new BufferedReader(new InputStreamReader(conn.getErrorStream(), "UTF-8"))) {
          String line;
          while ((line = in.readLine()) != null) {
            errorResponse.append(line);
          }
        }
        System.err.println(
            "Error creating session (HTTP " + status + "): " + errorResponse.toString());
        return false;
      }
    } catch (Exception e) {
      System.err.println("Exception while creating session: " + e.getMessage());
      e.printStackTrace();
      return false;
    }
  }
}
