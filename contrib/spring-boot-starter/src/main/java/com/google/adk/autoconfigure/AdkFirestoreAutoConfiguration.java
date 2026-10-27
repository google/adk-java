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
package com.google.adk.autoconfigure;

import com.google.adk.autoconfigure.properties.AdkFirestoreProperties;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * Produces a {@link Firestore} client bean from {@code adk.firestore.*} properties when the
 * Firestore client library is on the classpath and the user has not declared their own.
 *
 * <p>Active whenever {@code com.google.cloud.firestore.Firestore} is on the classpath — typically
 * because the {@code google-adk-firestore-session-service} contrib module is a dependency. Users
 * who already declare a {@link Firestore} bean (e.g. via Spring Cloud GCP) take precedence through
 * {@code @ConditionalOnMissingBean}.
 */
@AutoConfiguration
@ConditionalOnClass(Firestore.class)
@EnableConfigurationProperties(AdkFirestoreProperties.class)
public class AdkFirestoreAutoConfiguration {

  // Lazy so the client is only built when a FIRESTORE backend requests it; building it eagerly
  // fails at startup when no GCP project can be resolved, even with IN_MEMORY backends.
  @Bean
  @Lazy
  @ConditionalOnMissingBean
  public Firestore firestore(AdkFirestoreProperties properties) {
    FirestoreOptions.Builder builder = FirestoreOptions.newBuilder();
    Optional.ofNullable(properties.getProjectId())
        .filter(s -> !s.isBlank())
        .ifPresent(builder::setProjectId);
    Optional.ofNullable(properties.getDatabaseId())
        .filter(s -> !s.isBlank())
        .ifPresent(builder::setDatabaseId);
    return builder.build().getService();
  }
}
