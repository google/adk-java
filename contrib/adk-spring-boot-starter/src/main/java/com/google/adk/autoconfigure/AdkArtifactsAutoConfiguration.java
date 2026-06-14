/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.adk.autoconfigure;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.GcsArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.autoconfigure.properties.AdkArtifactProperties;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the {@link BaseArtifactService} bean.
 *
 * <p>Default behaviour: produces an {@link InMemoryArtifactService}. When {@code
 * adk.artifacts.gcs-enabled=true}, produces a {@link GcsArtifactService} backed by a {@link
 * Storage} bean — either user-supplied or auto-created via
 * {@link StorageOptions#getDefaultInstance()}.
 */
@AutoConfiguration
@EnableConfigurationProperties(AdkArtifactProperties.class)
public class AdkArtifactsAutoConfiguration {

  @Bean
  @ConditionalOnProperty(prefix = "adk.artifacts", name = "gcs-enabled", havingValue = "true")
  @ConditionalOnMissingBean
  public Storage googleCloudStorage() {
    return StorageOptions.getDefaultInstance().getService();
  }

  @Bean
  @ConditionalOnMissingBean
  public BaseArtifactService artifactService(
      AdkArtifactProperties properties, ObjectProvider<Storage> storageProvider) {
    if (properties.isGcsEnabled()) {
      if (properties.getBucketName() == null || properties.getBucketName().isBlank()) {
        throw new BeanCreationException(
            "adk.artifacts.bucket-name must be set when adk.artifacts.gcs-enabled=true");
      }
      Storage storage = storageProvider.getObject();
      return new GcsArtifactService(properties.getBucketName(), storage);
    }
    return new InMemoryArtifactService();
  }
}
