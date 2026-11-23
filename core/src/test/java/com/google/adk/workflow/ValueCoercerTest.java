/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.workflow;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link ValueCoercer}, which checks run-time values against a node's schemas. */
@RunWith(JUnit4.class)
public final class ValueCoercerTest {

  private static final Schema INTEGER = typed(Type.Known.INTEGER);
  private static final Schema NUMBER = typed(Type.Known.NUMBER);
  private static final Schema BOOLEAN = typed(Type.Known.BOOLEAN);
  private static final Schema STRING = typed(Type.Known.STRING);
  private static final Schema NULL = typed(Type.Known.NULL);
  private static final Schema RED = STRING.toBuilder().enum_("red").build();
  private static final Schema COUNT =
      typed(Type.Known.OBJECT).toBuilder().properties(ImmutableMap.of("count", INTEGER)).build();
  private static final Schema INTEGERS = typed(Type.Known.ARRAY).toBuilder().items(INTEGER).build();

  /** A value no failure message may quote. */
  private static final String SECRET = "SENTINEL-4711";

  @Test
  public void coerceValue_object_convertsDeclaredKeysKeepsOthersAndFillsNoDefaults() {
    // Arrange
    Map<String, Object> input = new HashMap<>(ImmutableMap.of("count", "1", "extra", "x"));
    Schema limit =
        COUNT.toBuilder()
            .properties(ImmutableMap.of("limit", INTEGER.toBuilder().default_(10).build()))
            .build();

    // Act
    Object checked = check(input, COUNT);
    Object withoutDefault = check(ImmutableMap.of(), limit);

    // Assert
    assertThat(checked).isEqualTo(ImmutableMap.of("count", 1L, "extra", "x"));
    assertThat(input).containsExactly("count", "1", "extra", "x");
    assertThat(withoutDefault).isEqualTo(ImmutableMap.of());
    assertRejects(COUNT, "{\"count\": 1}", ImmutableList.of());
  }

  @Test
  public void coerceValue_scalars_readJsonLiteralsAndStringsTakeOnlyText() {
    // Act
    BigInteger large = new BigInteger("9223372036854775808");
    List<?> integers =
        checkEach(
            INTEGER, 42, 42L, (short) 4, (byte) 2, BigInteger.TEN, large, 42.0, 42f, "1e2", "4.0");
    List<?> numbers = checkEach(NUMBER, 3, 1.5, 1.5f, "1.5", "3");
    List<?> booleans = checkEach(BOOLEAN, true, "false");

    // Assert
    assertThat(integers)
        .containsExactly(42, 42L, (short) 4, (byte) 2, BigInteger.TEN, large, 42L, 42L, 100L, 4L)
        .inOrder();
    assertThat(numbers).containsExactly(3, 1.5, 1.5f, 1.5, 3L).inOrder();
    assertThat(booleans).containsExactly(true, false).inOrder();
    assertRejects(INTEGER, 3.5, "3.5", true, "abc", "003", "+3", "007", " 3", "42d", "0x1p4");
    assertRejects(INTEGER, "5.", "1_000", "1e19", 1e19, -1e19, new BigDecimal("4"));
    assertRejects(INTEGER, "9223372036854775808", "-9223372036854775809", "-9223372036854775809.0");
    assertRejects(NUMBER, "nan", "Infinity", Double.NaN, Float.POSITIVE_INFINITY, false);
    assertRejects(NUMBER, "1".repeat(310), "+1.5", "42d", ".5", "1.5f");
    assertRejects(BOOLEAN, "TRUE", "yes", 1, "1");
    assertRejects(STRING, 3, 1.5, true, 'c');
  }

  @Test
  public void coerceValue_enumsAndListItems_areEnforced() {
    // Arrange
    Schema oneOrTwo = INTEGER.toBuilder().enum_("1", "2").build();

    // Act
    Object red = check("red", RED);
    Object redFromContent = check(text("red"), RED);
    Object two = check("2", oneOrTwo);
    Object twoFromDouble = check(2.0, oneOrTwo);
    Object list = check(ImmutableSet.of("1", 2.0), INTEGERS);
    Object untypedItemsList = check(ImmutableList.of("a", 1), typed(Type.Known.ARRAY));

    // Assert
    assertThat(red).isEqualTo("red");
    assertThat(redFromContent).isEqualTo("red");
    assertThat(two).isEqualTo(2L);
    assertThat(twoFromDouble).isEqualTo(2L);
    assertThat(list).isEqualTo(ImmutableList.of(1L, 2L));
    assertThat(untypedItemsList).isEqualTo(ImmutableList.of("a", 1));
    assertRejects(RED, "blue");
    assertRejects(oneOrTwo, 3);
    assertRejects(INTEGERS, ImmutableList.of("a"), "1");
  }

  @Test
  public void coerceValue_untypedSchemaWithKeysOrItems_stillChecksThem() {
    // Arrange
    Schema untypedCount =
        Schema.builder().properties(ImmutableMap.of("count", INTEGER)).required("count").build();
    Schema untypedRequiredOnly = Schema.builder().required("id").build();
    Schema untypedIntegers = Schema.builder().items(INTEGER).build();

    // Act
    Object count = check(ImmutableMap.of("count", "3"), untypedCount);
    Object withId = check(ImmutableMap.of("id", "a"), untypedRequiredOnly);
    Object integers = check(ImmutableList.of("1", 2.0), untypedIntegers);
    Object text = check("free text", untypedCount);

    // Assert
    assertThat(count).isEqualTo(ImmutableMap.of("count", 3L));
    assertThat(withId).isEqualTo(ImmutableMap.of("id", "a"));
    assertThat(integers).isEqualTo(ImmutableList.of(1L, 2L));
    assertThat(text).isEqualTo("free text");
    assertRejects(untypedCount, ImmutableMap.of(), ImmutableMap.of("count", "abc"));
    assertRejects(untypedRequiredOnly, ImmutableMap.of());
    assertRejects(untypedIntegers, ImmutableList.of("x"));
  }

  @Test
  public void coerceValue_null_passesOnlyANullableNullOrUntypedSchemaWithoutAnEnum() {
    // Arrange
    Schema untypedEnum = Schema.builder().enum_("red").build();
    Schema objectWithUntypedBranch =
        typed(Type.Known.OBJECT).toBuilder().anyOf(Schema.builder().required("id").build()).build();
    Schema stringWithNullableBranch =
        STRING.toBuilder().anyOf(STRING.toBuilder().nullable(true).build()).build();
    Schema requiredNullable =
        typed(Type.Known.OBJECT).toBuilder()
            .properties(ImmutableMap.of("count", INTEGER.toBuilder().nullable(true).build()))
            .required("count")
            .build();

    // Act
    Object intoNullable = check(null, INTEGER.toBuilder().nullable(true).build());
    Object intoNullableUnion =
        check(null, Schema.builder().nullable(true).anyOf(INTEGER, STRING).build());
    Object intoNull = check(null, NULL);
    Object intoUntyped = check(null, Schema.builder().build());
    Object intoUnion = check(null, Schema.builder().anyOf(NULL, STRING).build());
    Object intoRequiredNullable = check(Collections.singletonMap("count", null), requiredNullable);

    // Assert
    assertThat(intoNullable).isNull();
    assertThat(intoNullableUnion).isNull();
    assertThat(intoNull).isNull();
    assertThat(intoUntyped).isNull();
    assertThat(intoUnion).isNull();
    assertThat(intoRequiredNullable).isEqualTo(Collections.singletonMap("count", null));
    assertRejects(NULL, "x");
    assertRejects(COUNT, Collections.singletonMap("count", null));
    assertRejects(INTEGER, (Object) null);
    assertRejects(untypedEnum, (Object) null);
    assertRejects(Schema.builder().anyOf(INTEGER, STRING).build(), (Object) null);
    assertRejects(STRING.toBuilder().anyOf(untypedEnum).build(), (Object) null);
    assertRejects(objectWithUntypedBranch, (Object) null);
    assertRejects(stringWithNullableBranch, (Object) null);
  }

  @Test
  public void coerceValue_anyOf_prefersAnAlternativeThatTakesTheValueAsItIs() {
    // Act
    Object keptString = check("3", Schema.builder().anyOf(INTEGER, STRING).build());
    Object keptMap =
        check(
            ImmutableMap.of("count", "42"),
            Schema.builder().anyOf(COUNT, typed(Type.Known.OBJECT)).build());
    Object keptList =
        check(
            ImmutableList.of("1"),
            Schema.builder().anyOf(INTEGERS, typed(Type.Known.ARRAY)).build());
    Object converted = check("3", Schema.builder().anyOf(BOOLEAN, INTEGER).build());
    Object firstNumber = check("1.0", Schema.builder().anyOf(NUMBER, INTEGER).build());
    Object firstInteger = check("1.0", Schema.builder().anyOf(INTEGER, NUMBER).build());

    // Assert
    assertThat(keptString).isEqualTo("3");
    assertThat(keptMap).isEqualTo(ImmutableMap.of("count", "42"));
    assertThat(keptList).isEqualTo(ImmutableList.of("1"));
    assertThat(converted).isEqualTo(3L);
    assertThat(firstNumber).isEqualTo(1.0);
    assertThat(firstInteger).isEqualTo(1L);
    assertRejects(Schema.builder().anyOf(INTEGER, BOOLEAN).build(), ImmutableList.of(), "x");
  }

  @Test
  public void coerceValue_content_isReadAsJsonUnlessTheSchemaTakesAString() {
    // Arrange
    String json = "{\"count\": \"42\"}";

    // Act
    Object asObject = check(text(json), COUNT);
    Object asString = check(text(json), STRING);
    Object asUnion = check(text("42"), Schema.builder().anyOf(INTEGER, STRING).build());
    Object asBigInt = check(text("9223372036854775808"), INTEGER);
    Object untyped = check(text("hello"), Schema.builder().build());

    // Assert
    assertThat(asObject).isEqualTo(ImmutableMap.of("count", 42L));
    assertThat(asString).isEqualTo(json);
    assertThat(asUnion).isEqualTo("42");
    assertThat(asBigInt).isEqualTo(new BigInteger("9223372036854775808"));
    assertThat(untyped).isEqualTo("hello");
    assertRejects(COUNT, text("hello"), text("null"));
  }

  @Test
  public void coerceValue_unionBudget_capsTriesPerCheckButNotPerListItem() {
    // Arrange
    List<Schema> withinBudget = new ArrayList<>(Collections.nCopies(999, INTEGER));
    withinBudget.add(STRING);
    List<Schema> overBudget = new ArrayList<>(withinBudget);
    overBudget.add(0, INTEGER);

    List<String> longList = Collections.nCopies(2000, "x");
    Schema stringItems =
        typed(Type.Known.ARRAY).toBuilder()
            .items(Schema.builder().anyOf(STRING, NULL).build())
            .build();
    // Two properties of one object share one budget: 2 x 600 tries is too many.
    List<Schema> sixHundredTries = new ArrayList<>(Collections.nCopies(599, INTEGER));
    sixHundredTries.add(STRING);
    Schema sixHundred = Schema.builder().anyOf(sixHundredTries).build();
    Schema pair =
        COUNT.toBuilder().properties(ImmutableMap.of("a", sixHundred, "b", sixHundred)).build();
    // An outer anyOf must not swallow a budget failure even after an earlier branch converted.
    Schema outerOverBudget =
        Schema.builder().anyOf(INTEGER, Schema.builder().anyOf(overBudget).build()).build();

    // Act
    Object checked = check("x", Schema.builder().anyOf(withinBudget).build());
    Object checkedList = check(longList, stringItems);
    String tooComplex = failureMessage(SECRET, Schema.builder().anyOf(overBudget).build());
    String sharedBudget = failureMessage(ImmutableMap.of("a", SECRET, "b", SECRET), pair);
    String outerBudget = failureMessage("3", outerOverBudget);

    // Assert
    assertThat(checked).isEqualTo("x");
    assertThat(checkedList).isEqualTo(longList);
    assertThat(tooComplex).contains("tried more than 1000 union alternatives");
    assertThat(sharedBudget).contains("tried more than 1000 union alternatives");
    assertThat(outerBudget).contains("tried more than 1000 union alternatives");
  }

  @Test
  public void coerceValue_failure_namesWhereButNeverTheValueOrAnUndeclaredKey() {
    // Arrange
    Schema person =
        typed(Type.Known.OBJECT).toBuilder()
            .properties(ImmutableMap.of("name", STRING, "tags", INTEGERS))
            .required("name")
            .build();
    // Each level reuses its child on both branches, so 12 levels describe 4096 combinations.
    Schema nested = STRING;
    for (int i = 0; i < 12; i++) {
      nested = Schema.builder().anyOf(nested, nested).build();
    }

    // Act
    String wrongKey = failureMessage(ImmutableMap.of("count", SECRET, SECRET, 1), COUNT);
    String missing = failureMessage(ImmutableMap.of(SECRET, SECRET), person);
    String wrongItem =
        failureMessage(ImmutableMap.of("name", "", "tags", ImmutableList.of(1, SECRET)), person);
    String wrongRootItem = failureMessage(ImmutableList.of(SECRET), INTEGERS);
    String notAnObject = failureMessage(ImmutableMap.of("count", SECRET), INTEGER);
    String notInEnum = failureMessage(SECRET, RED);
    String noAlternative = failureMessage(SECRET, Schema.builder().anyOf(INTEGER, NULL).build());
    String tooComplex = failureMessage(ImmutableList.of(SECRET), nested);

    // Assert
    String prefix = "validation error: input of node 'n' does not match its schema: ";
    assertThat(wrongKey).isEqualTo(prefix + "'count' is not an integer");
    assertThat(missing).isEqualTo(prefix + "the value lacks required 'name'");
    assertThat(wrongItem).isEqualTo(prefix + "'tags[1]' is not an integer");
    assertThat(wrongRootItem).isEqualTo(prefix + "'[0]' is not an integer");
    assertThat(notAnObject).isEqualTo(prefix + "the value is not an integer");
    assertThat(notInEnum).isEqualTo(prefix + "the value is not one of the schema's enum values");
    assertThat(noAlternative)
        .isEqualTo(prefix + "the value matches none of the schema's alternatives");
    assertThat(tooComplex)
        .isEqualTo(
            prefix
                + "it tried more than 1000 union alternatives, so its `anyOf` branches multiply out"
                + " beyond what can be checked");
  }

  private static Schema typed(Type.Known type) {
    return Schema.builder().type(type).build();
  }

  private static Content text(String text) {
    return Content.fromParts(Part.fromText(text));
  }

  private static @Nullable Object check(@Nullable Object value, Schema schema) {
    return ValueCoercer.coerceValue(value, schema, "value");
  }

  private static List<@Nullable Object> checkEach(Schema schema, Object... values) {
    List<@Nullable Object> checked = new ArrayList<>();
    for (Object value : values) {
      checked.add(check(value, schema));
    }
    return checked;
  }

  private static boolean fails(@Nullable Object value, Schema schema) {
    try {
      Object unused = check(value, schema);
      return false;
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  /** Asserts that {@code schema} rejects each value; a failure lists the ones it accepted. */
  private static void assertRejects(Schema schema, Object... values) {
    assertThat(Arrays.stream(values).filter(value -> !fails(value, schema)).toList()).isEmpty();
  }

  /** Returns the failure message for {@code value}, checking that it never quotes the secret. */
  private static String failureMessage(Object value, Schema schema) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ValueCoercer.coerceValue(value, schema, "input of node 'n'"));
    assertThat(error).hasCauseThat().isNull();
    assertThat(error).hasMessageThat().doesNotContain(SECRET);
    return error.getMessage();
  }
}
