/*
 * Copyright 2025 Google LLC
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

package com.google.adk.tools;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Annotations for tools. */
public final class Annotations {

  /**
   * Describes a tool or its inputs. On object properties, overrides Jackson's name and required
   * flag for tool schemas and tool JSON binding only; ordinary JSON serialization is unchanged.
   */
  @Target({METHOD, PARAMETER, FIELD, RECORD_COMPONENT})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Schema {
    /** Model-facing name; an empty property name retains Jackson's name or the Java default. */
    String name() default "";

    /** Description included in the generated tool schema. */
    String description() default "";

    /** Whether this input may be omitted from the generated schema's required list. */
    boolean optional() default false;
  }

  private Annotations() {}
}
