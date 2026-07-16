/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.memory;

/**
 * @author manoj.kumar
 */
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class VectorOperations {

  /**
   * Filters a list of vectors based on complex metadata criteria.
   *
   * @param vectors The list of vectors to filter.
   * @param criteria The predicate to filter vectors based on metadata.
   * @return A filtered list of vectors.
   */
  public List<Vector> filterComplexMetadata(
      List<Vector> vectors, Predicate<Map<String, Object>> criteria) {
    List<Vector> filteredVectors = new ArrayList<>();
    for (Vector vector : vectors) {
      if (criteria.test(vector.getMetadata())) {
        filteredVectors.add(vector);
      }
    }
    return filteredVectors;
  }

  /** Example usage of filterComplexMetadata. */
  public static void main(String[] args) {
    List<Vector> vectors = new ArrayList<>();
    // Assuming vectors are populated somewhere...
    vectors.add(
        new Vector(
            "1", "Content 1", new double[] {1.0, 2.0}, Map.of("category", "text", "length", 10)));
    vectors.add(
        new Vector(
            "2", "Content 2", new double[] {3.0, 4.0}, Map.of("category", "image", "length", 15)));
    vectors.add(
        new Vector(
            "3", "Content 3", new double[] {5.0, 6.0}, Map.of("category", "text", "length", 20)));

    // Example criteria predicate to filter vectors with a specific metadata key
    Predicate<Map<String, Object>> criteria =
        metadata -> metadata.containsKey("category") && metadata.get("category").equals("text");

    VectorOperations operations = new VectorOperations();
    List<Vector> filtered = operations.filterComplexMetadata(vectors, criteria);

    // Print filtered vectors
    System.out.println("Filtered Vectors:");
    filtered.forEach(vector -> System.out.println(vector.getId() + ": " + vector.getMetadata()));
  }
}
