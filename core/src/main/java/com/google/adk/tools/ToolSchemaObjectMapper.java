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
package com.google.adk.tools;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;

/** Creates isolated mappers that use ADK annotations without mutating the application's mapper. */
final class ToolSchemaObjectMapper {

  static ObjectMapper create(ObjectMapper source) {
    ObjectMapper mapper = source.copy();
    AnnotationIntrospector schema = new SchemaIntrospector();
    mapper.setAnnotationIntrospectors(
        AnnotationIntrospector.pair(
            schema, source.getSerializationConfig().getAnnotationIntrospector()),
        AnnotationIntrospector.pair(
            schema, source.getDeserializationConfig().getAnnotationIntrospector()));
    return mapper;
  }

  private static final class SchemaIntrospector extends NopAnnotationIntrospector {
    @Override
    public PropertyName findNameForSerialization(Annotated annotated) {
      return schemaName(annotated);
    }

    @Override
    public PropertyName findNameForDeserialization(Annotated annotated) {
      return schemaName(annotated);
    }

    @Override
    public Boolean hasRequiredMarker(AnnotatedMember member) {
      Annotations.Schema schema = member.getAnnotation(Annotations.Schema.class);
      return schema == null ? null : !schema.optional();
    }

    private static PropertyName schemaName(Annotated annotated) {
      Annotations.Schema schema = annotated.getAnnotation(Annotations.Schema.class);
      if (schema == null) {
        return null;
      }
      // An empty ADK name keeps Jackson's explicit name, or the default Java property name.
      return schema.name().isEmpty()
          ? PropertyName.USE_DEFAULT
          : PropertyName.construct(schema.name());
    }
  }

  private ToolSchemaObjectMapper() {}
}
