package com.google.adk.kafka.model;

/*
 * @author Arun Parmar
 * @date 2025-10-28
 * @description KafkaModel is a class that represents a Kafka message.
 * @version 1.0.0
 * @since 1.0.0
 * @see com.google.adk.utils.KafkaWriter
 * @see com.google.adk.utils.PropertiesHelper
 * @see com.google.adk.utils.CommonHelper
 * @see com.google.adk.utils.PostgresDBHelper
 */

public class KafkaModel {

  public boolean hasAddon;

  public boolean getHasAddon() {
    return hasAddon;
  }

  public void setHasAddon(boolean hasAddon) {
    this.hasAddon = hasAddon;
  }

  public String key;

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public String getTopic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public String value;
  public String topic;

  public KafkaModel() {}

  public KafkaModel(String key, String val, String topic) {
    this.key = key;
    this.value = val;
    this.topic = topic;
  }

  @Override
  public String toString() {
    return "[Topic:" + this.topic + ",Key:" + this.key + ",Value:" + this.value + "]";
  }
}
