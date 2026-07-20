package com.google.adk.kafka.consumer;

/*
 * @author Arun Parmar
 * @date 2025-10-28
 * @description KafkaWriter is a class that writes messages to Kafka.
 * @version 1.0.0
 * @since 1.0.0
 * @see com.google.adk.utils.PropertiesHelper
 */
import com.google.adk.utils.PropertiesHelper;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

public class KafkaWriter {

  private static Producer<String, String> producer = null;

  public static void OpenKafkaChannel() {

    try {
      Properties props = new Properties();
      System.out.println(
          "Connecting to kafka brokers:- "
              + PropertiesHelper.getInstance().getValue("kafkaBrokerAddress"));
      props.put("bootstrap.servers", PropertiesHelper.getInstance().getValue("kafkaBrokerAddress"));
      props.put("acks", "all");
      props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
      props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

      producer = new KafkaProducer<>(props);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static void Publish(String topic, String key, String value) {

    if (producer == null) {
      OpenKafkaChannel();
    }

    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    System.out.println(
        String.valueOf(dateFormat.format(new Date())) + " publish::start::" + key + "," + value);

    producer.send(new ProducerRecord<String, String>(topic, key, value));
  }

  public static void PublishWithStatus(String topic, String key, String value)
      throws InterruptedException, ExecutionException {

    if (producer == null) {
      OpenKafkaChannel();
    }

    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    System.out.println(String.valueOf(dateFormat.format(new Date())) + " publish::start::" + key);

    producer.send(new ProducerRecord<String, String>(topic, key, value)).get();
  }
}
