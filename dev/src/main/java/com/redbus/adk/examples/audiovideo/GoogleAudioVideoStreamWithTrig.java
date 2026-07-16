import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

// Import Math class for trig functions

/**
 * Agent for end-to-end testing of real-time voice streaming with a Google backend, now including
 * trigonometry functions.
 *
 * <p>This agent is configured to use a streaming model endpoint. The main method starts a WebSocket
 * client to connect to the AdkWebServer's /run_live endpoint, simulating a live interaction by
 * sending text and a dummy audio blob.
 *
 * <p>Author: Sandeep Belgavi Date: July 17, 2025
 */
public class GoogleAudioVideoStreamWithTrig {

  private static final String NAME = "GoogleAudioVideoStreamWithTrig";
  public static BaseAgent ROOT_AGENT = initAgent();

  public static BaseAgent initAgent() {
    // Read API key from environment variable for security.
    String apiKey = System.getenv("GOOGLE_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      throw new IllegalStateException("GOOGLE_API_KEY environment variable is not set.");
    }
    return LlmAgent.builder()
        .name(NAME)
        .model(new Gemini("gemini-2.5-flash-exp", apiKey))
        .description(
            "A voice agent that can use tools to answer questions about stocks and perform trigonometry calculations.")
        .instruction(
            "You are a helpful voice assistant. Use the provided tools to answer the user's"
                + " questions. Respond clearly and concisely. You can get stock prices and calculate sine, cosine, and tangent of angles.")
        .tools(
            FunctionTool.create(GoogleAudioVideoStreamWithTrig.class, "calculate_sin"),
            FunctionTool.create(GoogleAudioVideoStreamWithTrig.class, "calculateCosine"),
            FunctionTool.create(GoogleAudioVideoStreamWithTrig.class, "calculate_tan"))
        .build();
  }

  /**
   * Calculates the sine of an angle.
   *
   * @param angleValue The numeric value of the angle.
   * @param unit The unit of the angle ("degrees" or "radians").
   * @return A map containing the status and result.
   */
  public static Map<String, Object> calculate_sin(
      @Schema(description = "The numeric value of the angle") double angleValue,
      @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

    double angleInRadians;

    if ("degrees".equalsIgnoreCase(unit)) {
      angleInRadians = Math.toRadians(angleValue);
    } else if ("radians".equalsIgnoreCase(unit)) {
      angleInRadians = angleValue;
    } else {
      return Map.of(
          "status",
          "error",
          "report",
          "Invalid unit provided. Please specify 'degrees' or 'radians'.",
          "inputAngleValue",
          angleValue,
          "inputUnit",
          unit);
    }

    double result = Math.sin(angleInRadians);

    return Map.of(
        "status",
        "success",
        "report",
        String.format("The sine of %.4f %s is %.6f", angleValue, unit, result),
        "inputAngleValue",
        angleValue,
        "inputUnit",
        unit,
        "function",
        "sine",
        "result",
        result);
  }

  /**
   * Calculates the cosine of an angle.
   *
   * @param angleValue The numeric value of the angle.
   * @param unit The unit of the angle ("degrees" or "radians").
   * @return A map containing the status and result.
   */
  public static Map<String, Object> calculateCosine(
      @Schema(description = "The numeric value of the angle") double angleValue,
      @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

    double angleInRadians;

    if ("degrees".equalsIgnoreCase(unit)) {
      angleInRadians = Math.toRadians(angleValue);
    } else if ("radians".equalsIgnoreCase(unit)) {
      angleInRadians = angleValue;
    } else {
      return Map.of(
          "status",
          "error",
          "report",
          "Invalid unit provided. Please specify 'degrees' or 'radians'.",
          "inputAngleValue",
          angleValue,
          "inputUnit",
          unit);
    }

    double result = Math.cos(angleInRadians);

    return Map.of(
        "status",
        "success",
        "report",
        String.format("The cosine of %.4f %s is %.6f", angleValue, unit, result),
        "inputAngleValue",
        angleValue,
        "inputUnit",
        unit,
        "function",
        "cosine",
        "result",
        result);
  }

  /**
   * Calculates the tangent of an angle. Handles potential division by zero for tan(90 degrees),
   * etc.
   *
   * @param angleValue The numeric value of the angle.
   * @param unit The unit of the angle ("degrees" or "radians").
   * @return A map containing the status and result.
   */
  public static Map<String, Object> calculate_tan(
      @Schema(description = "The numeric value of the angle") double angleValue,
      @Schema(description = "The unit of the angle, either 'degrees' or 'radians'") String unit) {

    double angleInRadians;

    if ("degrees".equalsIgnoreCase(unit)) {
      // Check for angles where tangent is undefined (90 + 180*n degrees)
      double normalizedDegrees = angleValue % 180;
      if (Math.abs(normalizedDegrees - 90) < 1e-9 || Math.abs(normalizedDegrees + 90) < 1e-9) {
        return Map.of(
            "status",
            "error",
            "report",
            String.format("The tangent of %.4f degrees is undefined.", angleValue),
            "inputAngleValue",
            angleValue,
            "inputUnit",
            unit,
            "function",
            "tangent");
      }
      angleInRadians = Math.toRadians(angleValue);
    } else if ("radians".equalsIgnoreCase(unit)) {
      // Check for angles where tangent is undefined (pi/2 + pi*n radians)
      double normalizedRadians = angleValue % Math.PI;
      if (Math.abs(normalizedRadians - Math.PI / 2) < 1e-9
          || Math.abs(normalizedRadians + Math.PI / 2) < 1e-9) {
        return Map.of(
            "status",
            "error",
            "report",
            String.format("The tangent of %.4f radians is undefined.", angleValue),
            "inputAngleValue",
            angleValue,
            "inputUnit",
            unit,
            "function",
            "tangent");
      }
      angleInRadians = angleValue;
    } else {
      return Map.of(
          "status",
          "error",
          "report",
          "Invalid unit provided. Please specify 'degrees' or 'radians'.",
          "inputAngleValue",
          angleValue,
          "inputUnit",
          unit);
    }

    double result = Math.tan(angleInRadians);

    return Map.of(
        "status",
        "success",
        "report",
        String.format("The tangent of %.4f %s is %.6f", angleValue, unit, result),
        "inputAngleValue",
        angleValue,
        "inputUnit",
        unit,
        "function",
        "tangent",
        "result",
        result);
  }

  /**
   * Main method to run a WebSocket client for E2E testing of the voice stream.
   *
   * @param args Command line arguments (not used).
   * @throws InterruptedException if the thread is interrupted while waiting.
   */
  public static void main(String[] args) throws InterruptedException {
    String appName = NAME;
    String userId = "e2e-test-user";
    String sessionId = "e2e-session-" + System.currentTimeMillis();
    String wsUrl =
        String.format(
            "ws://localhost:8081/run_live?app_name=%s&user_id=%s&session_id=%s",
            appName, userId, sessionId);

    CountDownLatch latch = new CountDownLatch(1);
    ObjectMapper objectMapper = new ObjectMapper();

    WebSocket.Listener listener =
        new WebSocket.Listener() {
          @Override
          public void onOpen(WebSocket webSocket) {
            System.out.println("WebSocket Client: Connection OPEN");
            webSocket.request(1);
          }

          /**
           * Handles incoming text messages from the WebSocket server. Parses the message and checks
           * for the test goal condition.
           *
           * @param webSocket the WebSocket instance
           * @param data the received data
           * @param last whether this is the last part of the message
           * @return a CompletionStage for further processing
           */
          @Override
          public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("WebSocket Client << " + data);
            try {
              // Parse the server response and check for the test goal
              JsonNode response = objectMapper.readTree(data.toString());
              if (response.has("content")) {
                String text = response.get("content").get("parts").get(0).get("text").asText();
                if (text.contains("$2500")) {
                  System.out.println(
                      ">>> Test Goal Reached: Stock price tool was called correctly.");
                  latch.countDown(); // Release the latch to end the test
                }
              }
            } catch (Exception e) {
              System.err.println("Error parsing server message: " + e.getMessage());
            }
            webSocket.request(1);
            return null;
          }

          /**
           * Handles WebSocket connection closure events.
           *
           * @param webSocket the WebSocket instance
           * @param statusCode the status code for closure
           * @param reason the reason for closure
           * @return a CompletionStage for further processing
           */
          @Override
          public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("WebSocket Client: Connection CLOSED: " + statusCode + " " + reason);
            latch.countDown();
            return null;
          }

          /**
           * Handles WebSocket error events.
           *
           * @param webSocket the WebSocket instance
           * @param error the Throwable error
           */
          @Override
          public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket Client: ERROR: " + error.getMessage());
            error.printStackTrace();
            latch.countDown();
          }
        };

    HttpClient client = HttpClient.newHttpClient();
    System.out.println("Connecting to: " + wsUrl);
    WebSocket ws = client.newWebSocketBuilder().buildAsync(URI.create(wsUrl), listener).join();

    // 1. Send a text message to trigger a tool call
    String textMessage =
        "{\"content\": {\"role\": \"user\", \"parts\": [{\"text\": \"What is the stock price of GOOG?\"}]}}";
    System.out.println("WebSocket Client >> " + textMessage);
    ws.sendText(textMessage, true);

    // 2. Simulate sending a dummy audio blob
    // This tests the audio data path. A real client would send actual audio chunks.
    byte[] dummyAudio = new byte[1024]; // 1kb of silent audio
    String base64Audio = Base64.getEncoder().encodeToString(dummyAudio);
    String audioMessage =
        String.format("{\"blob\": {\"data\": \"%s\", \"mime_type\": \"audio/pcm\"}}", base64Audio);
    System.out.println("WebSocket Client >> (sending dummy audio blob)");
    ws.sendText(audioMessage, true);

    // Wait for the test to complete (or timeout)
    latch.await();

    // Close the connection if it's still open
    if (!ws.isOutputClosed()) {
      ws.sendClose(WebSocket.NORMAL_CLOSURE, "Test finished").join();
    }
    System.out.println("Test client finished.");
  }
}
