/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.memory;

/**
 * @author manoj.kumar
 */
import java.util.Comparator;

public class VectorIdComparator implements Comparator<Vector> {
  @Override
  public int compare(Vector v1, Vector v2) {
    return v1.getId().compareTo(v2.getId());
  }
}
