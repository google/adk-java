/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.memory;

/**
 * @author manoj.kumar
 */
import java.util.Map;

public class Vector {
  private String id; // Unique identifier for the vector
  private String content; // Content associated with the vector
  private double[] embedding; // Embedding values
  private Map<String, Object> metadata; // Additional metadata

  // Constructors, getters, and setters
  public Vector(String id, String content, double[] embedding, Map<String, Object> metadata) {
    this.id = id;
    this.content = content;
    this.embedding = embedding;
    this.metadata = metadata;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public double[] getEmbedding() {
    return embedding;
  }

  public void setEmbedding(double[] embedding) {
    this.embedding = embedding;
  }

  public Map<String, Object> getMetadata() {
    return metadata;
  }

  public void setMetadata(Map<String, Object> metadata) {
    this.metadata = metadata;
  }
}
