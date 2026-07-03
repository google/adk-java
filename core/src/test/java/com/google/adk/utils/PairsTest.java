package com.google.adk.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PairsTest {

  @Test
  public void of_empty_returnsEmptyMap() {
    ConcurrentHashMap<String, Integer> map = Pairs.of();
    assertNotNull(map);
    assertTrue(map.isEmpty());
    // Check mutability
    map.put("test", 1);
    assertEquals(Integer.valueOf(1), map.get("test"));
  }

  @Test
  public void of_singleEntry_returnsMapWithSingleEntry() {
    ConcurrentHashMap<String, Integer> map = Pairs.of("one", 1);
    assertNotNull(map);
    assertEquals(1, map.size());
    assertEquals(Integer.valueOf(1), map.get("one"));
    // Check mutability
    map.put("two", 2);
    assertEquals(Integer.valueOf(2), map.get("two"));
  }

  @Test
  public void of_singleEntry_nullKey_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> Pairs.of(null, 1));
  }

  @Test
  public void of_singleEntry_nullValue_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> Pairs.of("key", null));
  }

  @Test
  public void of_twoEntries_returnsMapWithTwoEntries() {
    ConcurrentHashMap<String, Integer> map = Pairs.of("one", 1, "two", 2);
    assertNotNull(map);
    assertEquals(2, map.size());
    assertEquals(Integer.valueOf(1), map.get("one"));
    assertEquals(Integer.valueOf(2), map.get("two"));
    // Check mutability
    map.put("three", 3);
    assertEquals(Integer.valueOf(3), map.get("three"));
  }

  @Test
  public void of_twoEntries_duplicateKey_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> Pairs.of("one", 1, "one", 2)); // Map.of() behavior
  }

  @Test
  public void of_twoEntries_nullKey_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class, () -> Pairs.of("one", 1, null, 2)); // Map.of() behavior
  }

  @Test
  public void of_twoEntries_nullValue_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class, () -> Pairs.of("one", 1, "two", null)); // Map.of() behavior
  }

  @Test
  public void of_tenEntries_returnsMapWithTenEntries() {
    ConcurrentHashMap<String, Integer> map =
        Pairs.of(
            "k1", 1, "k2", 2, "k3", 3, "k4", 4, "k5", 5, "k6", 6, "k7", 7, "k8", 8, "k9", 9, "k10",
            10);
    assertNotNull(map);
    assertEquals(10, map.size());
    assertEquals(Integer.valueOf(10), map.get("k10"));
    // Check mutability
    map.put("k11", 11);
    assertEquals(Integer.valueOf(11), map.get("k11"));
  }

  @Test
  public void of_tenEntries_duplicateKey_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Pairs.of(
                "k1",
                1,
                "k2",
                2,
                "k3",
                3,
                "k4",
                4,
                "k5",
                5,
                "k6",
                6,
                "k7",
                7,
                "k8",
                8,
                "k9",
                9,
                "k1",
                10 // Duplicate k1
                ));
  }

  @Test
  public void of_tenEntries_nullValue_throwsNullPointerException() {
    assertThrows(
        NullPointerException.class,
        () ->
            Pairs.of(
                "k1",
                1,
                "k2",
                2,
                "k3",
                3,
                "k4",
                4,
                "k5",
                5,
                "k6",
                6,
                "k7",
                7,
                "k8",
                8,
                "k9",
                9,
                "k10",
                null // Null value
                ));
  }
}
