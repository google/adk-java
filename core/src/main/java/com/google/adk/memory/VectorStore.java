/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.memory;

/**
 * @author manoj.kumar
 */
import java.util.List;

public interface VectorStore {
  void insertVector(Vector vector);

  Vector getVector(String id);

  void updateVector(Vector vector);

  void deleteVector(String id);

  void close();

  List<Vector> searchVectors(double[] queryVector, double threshold);
}
