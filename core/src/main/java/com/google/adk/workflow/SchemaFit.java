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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Decides at build time whether an output schema fits an input schema by types, enums, keys and
 * nullability; it ignores formats, patterns and bounds. An output with no type or enum fits any
 * input that accepts its nulls, and an object output that lists no keys fits any object input.
 */
final class SchemaFit {

  /** Reads one standard JSON value, with nothing after it. */
  private static final ObjectReader STRICT_JSON =
      PortCodec.MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /**
   * Whether {@code source} fits {@code target} by types, enums, keys and nullability. With {@code
   * closed}, each object in {@code target} that lists properties takes no other key, as in a schema
   * inferred from a type.
   */
  static boolean fits(Schema source, Schema target, boolean closed) {
    if (source.equals(target)) {
      return true;
    }
    if (source.anyOf().isPresent()) {
      return unionFits(source, source.anyOf().get(), target, closed);
    }
    Type.Known type = declaredType(source);
    if (isNullable(source) || type == Type.Known.NULL) {
      return acceptsNull(target)
          && (type == Type.Known.NULL
              || fits(source.toBuilder().clearNullable().build(), target, closed));
    }
    if (type == null && source.enum_().isPresent()) {
      // Each value must fit as each of its readings, such as 1 for "1".
      return source.enum_().get().stream()
          .allMatch(value ->
              readings(value).stream()
                  .allMatch(reading ->
                      fits(source.toBuilder().type(reading).enum_(value).build(), target, closed)));
    }
    if (target.anyOf().isPresent()) {
      return fits(source, target.toBuilder().clearAnyOf().build(), closed)
          && target.anyOf().get().stream().anyMatch(branch -> fits(source, branch, closed));
    }
    if (type == null) {
      return true;
    }
    if (target.enum_().isPresent()
        && !(source.enum_().isPresent()
            && target.enum_().get().containsAll(source.enum_().get()))) {
      return false;
    }
    Type.Known targetType = declaredType(target);
    if (targetType != null
        && targetType != type
        && !(type == Type.Known.INTEGER && targetType == Type.Known.NUMBER)) {
      return false;
    }
    return switch (type) {
      case ARRAY ->
          target.items().isEmpty()
              || source.items().isEmpty()
              || fits(source.items().get(), target.items().get(), closed);
      case OBJECT -> objectFits(source, target, closed);
      default -> true;
    };
  }

  /**
   * The paths, such as {@code note} or {@code invoice.total}, of the keys of the objects in {@code
   * source} that the matching objects in {@code target} lack.
   */
  static ImmutableSet<String> extraKeys(Schema source, Schema target) {
    ImmutableSet.Builder<String> keys = ImmutableSet.builder();
    addExtraKeys(source, target, "", keys);
    return keys.build();
  }

  private static void addExtraKeys(
      Schema source, Schema target, String prefix, ImmutableSet.Builder<String> keys) {
    source
        .anyOf()
        .ifPresent(branches -> branches.forEach(b -> addExtraKeys(b, target, prefix, keys)));
    if (source.items().isPresent() && target.items().isPresent()) {
      addExtraKeys(source.items().get(), target.items().get(), prefix, keys);
    }
    if (source.properties().isEmpty() || target.properties().isEmpty()) {
      return;
    }
    Map<String, Schema> wanted = target.properties().get();
    source
        .properties()
        .get()
        .forEach((key, value) -> {
          if (wanted.containsKey(key)) {
            addExtraKeys(value, wanted.get(key), prefix + key + ".", keys);
          } else {
            keys.add(prefix + key);
          }
        });
  }

  /** Whether a union fits: its own part and each of its {@code branches} must. */
  private static boolean unionFits(
      Schema source, List<Schema> branches, Schema target, boolean closed) {
    boolean takesNull = acceptsNull(source);
    if (takesNull && !acceptsNull(target)) {
      return false;
    }
    // A union that rejects null admits only its branches' other values.
    List<Schema> values =
        takesNull
            ? branches
            : branches.stream()
                .filter(branch -> declaredType(branch) != Type.Known.NULL)
                .map(branch -> branch.toBuilder().clearNullable().build())
                .collect(toImmutableList());
    // An untyped union's own keys, items or enum take the one type its branches share, if any.
    List<Type.@Nullable Known> types =
        values.stream()
            .filter(value -> declaredType(value) != Type.Known.NULL)
            .map(SchemaFit::impliedType)
            .distinct()
            .collect(Collectors.toCollection(ArrayList::new));
    Type.Known type = declaredType(source);
    Schema.Builder own = source.toBuilder().clearAnyOf();
    if (type == null
        && (impliedType(source) != null || source.enum_().isPresent())
        && types.size() == 1
        && types.get(0) != null) {
      own.type(types.get(0));
    }
    // An untyped branch takes its union's type.
    return fits(own.build(), target, closed)
        && values.stream()
            .allMatch(
                value ->
                    fits(
                        type != null && declaredType(value) == null
                            ? value.toBuilder().type(type).build()
                            : value,
                        target,
                        closed));
  }

  /** Whether {@code source}, an object, fits {@code target}'s keys. */
  private static boolean objectFits(Schema source, Schema target, boolean closed) {
    // An object that constrains no keys, such as a map, passes: the build cannot know its keys.
    if (!declaresKeys(source)) {
      return true;
    }
    Map<String, Schema> given = source.properties().orElse(ImmutableMap.of());
    Map<String, Schema> wanted = target.properties().orElse(ImmutableMap.of());
    List<String> required = source.required().orElse(ImmutableList.of());
    List<String> targetRequired = target.required().orElse(ImmutableList.of());
    // The source must require each key the target requires, and declare it if the target does.
    if (!required.containsAll(targetRequired)
        || targetRequired.stream()
            .anyMatch(key -> wanted.containsKey(key) && !given.containsKey(key))) {
      return false;
    }
    boolean closedTarget = closed && target.properties().isPresent();
    if (closedTarget && !wanted.keySet().containsAll(required)) {
      return false;
    }
    return given.entrySet().stream()
        .allMatch(
            property ->
                wanted.containsKey(property.getKey())
                    ? fits(property.getValue(), wanted.get(property.getKey()), closed)
                    : !closedTarget);
  }

  /**
   * Whether {@code schema} takes null: if nullable, typed NULL, or untyped with no enum, unless it
   * has anyOf branches and none of them takes null.
   */
  private static boolean acceptsNull(Schema schema) {
    return isNullable(schema)
        || declaredType(schema) == Type.Known.NULL
        || (declaredType(schema) == null
            && schema.enum_().isEmpty()
            && schema
                .anyOf()
                .map(branches -> branches.stream().anyMatch(SchemaFit::acceptsNull))
                .orElse(true));
  }

  /**
   * Returns the types an untyped enum value must fit as: the type of the JSON value it spells, plus
   * STRING unless it is a bare number or boolean. Other text is only text.
   */
  private static ImmutableSet<Type.Known> readings(@Nullable String value) {
    JsonNode literal = value == null ? null : jsonLiteral(value);
    if (value == null || literal == null) {
      return ImmutableSet.of(Type.Known.STRING);
    }
    Type.Known type =
        switch (literal.getNodeType()) {
          case OBJECT -> Type.Known.OBJECT;
          case ARRAY -> Type.Known.ARRAY;
          case BOOLEAN -> Type.Known.BOOLEAN;
          // An integer port does not read "1.0" or "1e2".
          case NUMBER ->
              literal.isIntegralNumber() && literal.canConvertToLong()
                  ? Type.Known.INTEGER
                  : Type.Known.NUMBER;
          default -> Type.Known.STRING;
        };
    return literal.isValueNode() && value.equals(value.strip())
        ? ImmutableSet.of(type)
        : ImmutableSet.of(Type.Known.STRING, type);
  }

  /** Returns the one JSON value {@code value} spells, or null if none or a number is not finite. */
  private static @Nullable JsonNode jsonLiteral(String value) {
    try {
      JsonNode node = STRICT_JSON.readTree(value);
      return isFinite(node) ? node : null;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static boolean isFinite(JsonNode node) {
    if (node.isNumber()) {
      return Double.isFinite(node.doubleValue());
    }
    for (JsonNode child : node) {
      if (!isFinite(child)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isNullable(Schema schema) {
    return schema.nullable().orElse(false);
  }

  private static boolean declaresKeys(Schema schema) {
    return schema.properties().isPresent() || schema.required().isPresent();
  }

  /** Returns the type {@code schema} declares, or else the shape its keys or items imply. */
  private static Type.@Nullable Known impliedType(Schema schema) {
    Type.Known type = declaredType(schema);
    if (type != null) {
      return type;
    }
    if (declaresKeys(schema)) {
      return Type.Known.OBJECT;
    }
    return schema.items().isPresent() ? Type.Known.ARRAY : null;
  }

  private static Type.@Nullable Known declaredType(Schema schema) {
    Type.Known type = schema.type().map(Type::knownEnum).orElse(null);
    return type == Type.Known.TYPE_UNSPECIFIED ? null : type;
  }

  private SchemaFit() {}
}
