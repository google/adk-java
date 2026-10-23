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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Decides at build time whether every value an output schema admits fits an input schema. What a
 * schema leaves open, such as an untyped value or an object that lists no keys, is left to run
 * time.
 */
final class SchemaFit {

  /**
   * Whether every value {@code source} admits fits {@code target}. With {@code closed}, each object
   * in {@code target} that lists properties takes no other key, as in a schema inferred from a
   * type.
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
      // An enum lists strings, as a typed one does.
      return fits(source.toBuilder().type(Type.Known.STRING).build(), target, closed);
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
            .collect(Collectors.toList());
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
    // An object that constrains no keys, such as a map, is left to run time.
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
