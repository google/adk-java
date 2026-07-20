package com.google.adk.kafka.repository;

import com.google.adk.kafka.consumer.KafkaWriter;
import com.google.adk.kafka.model.KafkaModel;
import com.google.adk.utils.PropertiesHelper;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import org.json.JSONObject;

public class CommonHelper {

  public static KafkaModel GetKafkaModelForADKEvent(String key, String value) {
    JSONObject valueJson = null;
    if (value != null && !value.isEmpty()) {
      // If value is not empty, set it in the JSON object
      valueJson = new JSONObject(value);
    }
    JSONObject jsonObject = new JSONObject();
    jsonObject.put("business_event", "adk-event");
    JSONObject sessionJson = new JSONObject();
    sessionJson.put("id", valueJson != null ? valueJson.getString("id") : key);
    sessionJson.put("appName", valueJson != null ? valueJson.getString("appName") : "adk_agent");
    sessionJson.put("userId", valueJson != null ? valueJson.getString("userId") : "adk_agent_user");
    sessionJson.put("state", new JSONObject());

    JSONObject eventJson = new JSONObject();
    eventJson.put("events", valueJson != null ? valueJson.get("events") : new JSONObject());

    sessionJson.put("event_data", eventJson.toString());

    Instant now = Instant.now();
    sessionJson.put(
        "lastUpdateTime", (double) now.getEpochSecond() + now.getNano() / 1_000_000_000.0);
    jsonObject.put("event_attributes", sessionJson);

    value = jsonObject.toString();

    String topic = PropertiesHelper.getInstance().getValue("kafka_topic");
    KafkaModel kModel = new KafkaModel(key, value, topic);
    boolean kafkaStatusInFile = false;
    System.out.println("Pushing Message To Kafka: " + kModel);
    boolean isKafkaUp = true;
    String errorMessage = null;
    try {
      KafkaWriter.PublishWithStatus(kModel.getTopic(), kModel.getKey(), kModel.getValue());
    } catch (Exception ex) {
      isKafkaUp = false;
      errorMessage = ex.getMessage();
    }
    if (kafkaStatusInFile != isKafkaUp) {
      CommonHelper.SetKafkaStatus(isKafkaUp);
      kafkaStatusInFile = isKafkaUp;
    }
    if (isKafkaUp) {
      System.out.println("Message Pushed To Kafka");
    } else {
      System.out.println("Message not pushed To Kafka. Kafka is down." + errorMessage);
    }
    return kModel;
  }

  public static void SetKafkaStatus(boolean msg) {
    String fileName = "kafkastatus.txt";
    try {
      FileWriter fileWriter = new FileWriter(fileName, true); // Set true for append mode
      PrintWriter printWriter = new PrintWriter(fileWriter);
      printWriter.println(
          new SimpleDateFormat("dd:MM:yy HH:mm:ss").format(new Date()) + "," + msg); // New line
      printWriter.close();
    } catch (Exception ex) {
      System.out.println(
          "Exception while writing to file:" + fileName + ".Details:" + ex.getMessage());
      ex.printStackTrace();
    }
  }
}
