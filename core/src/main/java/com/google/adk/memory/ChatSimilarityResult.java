/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.memory;

/**
 * @author manoj.kumar
 */
// Helper class to hold chat similarity results
public class ChatSimilarityResult {
  private String chatId;
  private double score;

  public ChatSimilarityResult(String chatId, double score) {
    this.chatId = chatId;
    this.score = score;
  }

  public String getChatId() {
    return chatId;
  }

  public double getScore() {
    return score;
  }
}
