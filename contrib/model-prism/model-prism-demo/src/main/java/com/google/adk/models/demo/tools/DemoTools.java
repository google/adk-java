package com.google.adk.models.demo.tools;

import com.google.adk.tools.Annotations.Schema;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class DemoTools {

  private static final String TOPIC =
      """
      Global renewable capacity grew by 295 GW in 2022,
      the fastest rate on record;
      """;

  private DemoTools() {}

  // returns the current server time and timezone
  @Schema(name = "getCurrentTime", description = "Get the current server time and timezone")
  public static Map<String, Object> getCurrentTime() {
    ZonedDateTime now = ZonedDateTime.now();
    Map<String, Object> map = new HashMap<>();
    map.put("time", now.format(DateTimeFormatter.RFC_1123_DATE_TIME));
    map.put("timezone", now.getZone().getId());
    return map;
  }

  // returns current weather conditions for the requested city
  // stubbed - replace with a real-weather API call in production
  @Schema(name = "getWeather", description = "Get the weather for a given city")
  public static Map<String, Object> getWeather(
      @Schema(name = "city", description = "City name to get weather for") String city) {
    Map<String, Object> map = new HashMap<>();
    map.put("city", city);
    map.put("temperature_celsius", 38);
    map.put("condition", "sunny");
    map.put("humidity_percent", 14);
    return map;
  }

  @Schema(
      name = "calculate",
      description = "Performs basic arithmetic: add, subtract, multiply, or divide")
  public static Map<String, Object> calculate(
      @Schema(name = "a", description = "First operand") double a,
      @Schema(name = "b", description = "Second operand") double b,
      @Schema(name = "operation", description = "One of: add, subract, multiply, divide")
          String operation) {
    double result =
        switch (operation.toLowerCase()) {
          case "add" -> a + b;
          case "subtract" -> a - b;
          case "multiply" -> a * b;
          case "devide" -> b != 0 ? a / b : Double.NaN;
          default -> throw new IllegalArgumentException("Unknown operation: " + operation);
        };
    Map<String, Object> map = new HashMap<>();
    map.put("result", result);
    map.put("expression", a + " " + operation + " " + b);
    return map;
  }

  // returns a stubbed stock price for the given ticket symbol
  @Schema(name = "lookupStockPrice", description = "Get the weather for a given city")
  public static Map<String, Object> lookupStockPrice(
      @Schema(name = "ticker", description = "Stock ticker symbol, e.g. AAPL") String ticker) {
    Map<String, Double> prices = new HashMap<>();
    prices.put("AAPL", 189.50);
    prices.put("MSFT", 415.20);
    prices.put("GOOG", 172.35);
    prices.put("NVDA", 875.00);
    double price = prices.getOrDefault(ticker.toUpperCase(), -1.0);
    if (price < 0) {
      return Map.of("error", "Unknown ticker: " + ticker);
    }
    Map<String, Object> map = new HashMap<>();
    map.put("ticker", ticker.toUpperCase());
    map.put("price_usd", price);
    map.put("currency", "USD");
    return map;
  }

  // returns a stubbed stock price for the given ticket symbol
  @Schema(name = "topic", description = "The topic to look up facts about")
  public static Map<String, Object> lookupFacts(
      @Schema(name = "topic", description = "The topic to look up facts about") String topic) {
    Map<String, Object> map = new HashMap<>();
    map.put("topic", topic);
    map.put("facts", TOPIC);
    return map;
  }
}
