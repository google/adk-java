/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.memory;

/**
 * @author manoj.kumar
 */
/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 * @author manoj.kumar
 */
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

/**
 * +----------------+ +-----------------+ | | | | | Client Apps |<-----> | Vector Store | | | | API
 * | +----------------+ +--------+--------+ | | +-------+--------+ | | | MapDB Store | | |
 * +----------------+
 *
 * @author manoj.kumar
 */
public class MapDBVectorStore implements VectorStore {

  private final DB db;
  private final ConcurrentNavigableMap<String, Vector> vectorMap;
  private final ConcurrentNavigableMap<String, List<String>> chatMap; // Map to store chats
  private final ConcurrentNavigableMap<String, double[]>
      chatEmbeddingsMap; // Map to store chat embeddings
  private final ConcurrentNavigableMap<String, String> restToolMap;

  public MapDBVectorStore(String dbFilePath, String vectorCollection) {
    db =
        DBMaker.fileDB(dbFilePath)
            .fileChannelEnable()
            .transactionEnable()
            .fileMmapEnable()
            .fileMmapEnableIfSupported()
            .fileMmapPreclearDisable()
            .cleanerHackEnable()
            .make();
    vectorMap =
        db.treeMap(vectorCollection, Serializer.STRING, new VectorGroupSerializer()).createOrOpen();
    chatMap =
        db.treeMap("chats", Serializer.STRING, Serializer.JAVA)
            .createOrOpen(); // Serializer for chat list
    chatEmbeddingsMap =
        db.treeMap("chat_embeddings", Serializer.STRING, Serializer.DOUBLE_ARRAY)
            .createOrOpen(); // Store embeddings
    restToolMap = db.treeMap("restToolMap", Serializer.STRING, Serializer.STRING).createOrOpen();
  }

  @Override
  public void insertVector(Vector vector) {
    vectorMap.put(vector.getId(), vector);
    db.commit();
  }

  @Override
  public Vector getVector(String id) {
    return vectorMap.get(id);
  }

  @Override
  public void updateVector(Vector vector) {
    vectorMap.put(vector.getId(), vector);
    db.commit();
  }

  @Override
  public void deleteVector(String id) {
    vectorMap.remove(id);
    db.commit();
  }

  /**
   * Calculates the cosine similarity between two vectors.
   *
   * @param vectorA the first vector
   * @param vectorB the second vector
   * @return the cosine similarity between the two vectors
   */
  private double cosineSimilarity(double[] vectorA, double[] vectorB) {
    double dotProduct = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < vectorA.length; i++) {
      dotProduct += vectorA[i] * vectorB[i];
      normA += Math.pow(vectorA[i], 2);
      normB += Math.pow(vectorB[i], 2);
    }
    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  /**
   * Searches for vectors in the vector store that have a cosine similarity above the given
   * threshold with the query vector.
   *
   * @param queryVector the query vector
   * @param threshold the similarity threshold
   * @return a list of vectors that have a similarity above the threshold
   */
  public List<Vector> searchVectors(double[] queryVector, double threshold) {
    List<Vector> result = new ArrayList<>();
    for (Vector vector : vectorMap.values()) {
      double similarity = cosineSimilarity(vector.getEmbedding(), queryVector);
      if (similarity >= threshold) {
        result.add(vector);
        vector.getMetadata().put("score", similarity);
      }
    }
    return result;
  }

  /**
   * Searches for the vector in the vector store that has the highest cosine similarity with the
   * query vector, provided the similarity is above the given threshold.
   *
   * @param queryVector the query vector
   * @param threshold the similarity threshold
   * @return the vector with the highest similarity above the threshold, or null if none found
   */
  public Vector searchTopScoringVector(double[] queryVector, double threshold) {
    Vector topScoringVector = null;
    double highestSimilarity = threshold;

    for (Vector vector : vectorMap.values()) {
      double similarity = cosineSimilarity(vector.getEmbedding(), queryVector);
      if (similarity > highestSimilarity) {
        highestSimilarity = similarity;
        topScoringVector = vector;
      }
    }

    if (topScoringVector != null) {
      topScoringVector.getMetadata().put("score", highestSimilarity);
    }

    return topScoringVector;
  }

  /**
   * Searches for the top N vectors in the vector store that have the highest cosine similarity with
   * the query vector, provided the similarity is above the given threshold.
   *
   * @param queryVector the query vector
   * @param threshold the similarity threshold
   * @param topN the number of top matching vectors to return
   * @return a list of the top N vectors with similarity above the threshold
   */
  public List<Vector> searchTopNVectors(double[] queryVector, double threshold, int topN) {
    PriorityQueue<Vector> topVectors =
        new PriorityQueue<>(
            topN, Comparator.comparingDouble(v -> (double) v.getMetadata().get("score")));

    for (Vector vector : vectorMap.values()) {
      double similarity = cosineSimilarity(vector.getEmbedding(), queryVector);
      if (similarity >= threshold) {
        vector.getMetadata().put("score", similarity);
        if (topVectors.size() < topN) {
          topVectors.offer(vector);
        } else if (similarity > (double) topVectors.peek().getMetadata().get("score")) {
          topVectors.poll();
          topVectors.offer(vector);
        }
      }
    }

    List<Vector> result = new ArrayList<>(topVectors);
    result.sort(
        (v1, v2) ->
            Double.compare(
                (double) v2.getMetadata().get("score"), (double) v1.getMetadata().get("score")));
    return result;
  }

  // Chat related methods
  // Chat related methods
  /**
   * Stores a new chat with a unique chatId and its embedding.
   *
   * @param chatId the unique identifier of the chat.
   * @param messages the list of chat messages.
   * @param embedding the embedding array of the chat content.
   */
  public void storeChat(String chatId, List<String> messages, double[] embedding) {
    chatMap.put(chatId, messages);
    chatEmbeddingsMap.put(chatId, embedding); // Store embedding alongside the chat
    db.commit();
  }

  /**
   * Retrieves a chat by its unique chatId.
   *
   * @param chatId the unique identifier of the chat.
   * @return the list of messages in the chat, or null if the chat doesn't exist.
   */
  public List<String> getChat(String chatId) {
    return chatMap.get(chatId);
  }

  /**
   * Updates an existing chat by appending new messages.
   *
   * @param chatId the unique identifier of the chat.
   * @param newMessages the new messages to add to the chat.
   */
  public void updateChat(String chatId, List<String> newMessages) {
    List<String> existingMessages = chatMap.get(chatId);
    if (existingMessages != null) {
      existingMessages.addAll(newMessages); // Append new messages to existing ones
      chatMap.put(chatId, existingMessages);
      db.commit();
    }
  }

  /**
   * Finds similar chats based on cosine similarity between embeddings.
   *
   * @param queryEmbedding the embedding of the query chat.
   * @param threshold the minimum cosine similarity score to consider.
   * @param topN the number of most similar chats to return.
   * @return a list of chat IDs and their similarity scores, sorted by highest similarity.
   */
  public List<ChatSimilarityResult> findSimilarChats(
      double[] queryEmbedding, double threshold, int topN) {
    PriorityQueue<ChatSimilarityResult> topChats =
        new PriorityQueue<>(topN, Comparator.comparingDouble(ChatSimilarityResult::getScore));

    for (Map.Entry<String, double[]> entry : chatEmbeddingsMap.entrySet()) {
      String chatId = entry.getKey();
      double[] chatEmbedding = entry.getValue();
      double similarity = cosineSimilarity(queryEmbedding, chatEmbedding);

      if (similarity >= threshold) {
        if (topChats.size() < topN) {
          topChats.offer(new ChatSimilarityResult(chatId, similarity));
        } else if (similarity > topChats.peek().getScore()) {
          topChats.poll();
          topChats.offer(new ChatSimilarityResult(chatId, similarity));
        }
      }
    }

    List<ChatSimilarityResult> result = new ArrayList<>(topChats);
    result.sort(
        (c1, c2) -> Double.compare(c2.getScore(), c1.getScore())); // Sort by similarity descending
    return result;
  }

  public void close() {
    db.close();
  }

  // Tools
  /**
   * @param restId
   * @param toolId
   */
  public void saveRestToolMapping(String restId, String toolId) {
    restToolMap.put(restId, toolId);
    db.commit();
  }
}
