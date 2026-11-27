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

package com.google.adk.runner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.GcsArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.FirestoreMemoryService;
import com.google.adk.platform.UuidProvider;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.utils.FirestoreProperties;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Scheduler;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

//TODO: Remove the need for GCS bucket name

/**
 * Runner subclass pre-configured with Google Cloud Firestore for session and memory management.
 *
 * <p>Supports two usage styles:
 *
 * <ul>
 *   <li><b>Constructor-based (legacy, deprecated)</b>: direct instantiation for simple setups.
 *       Uses {@link GcsArtifactService} if {@code gcs.adk.bucket.name} is configured in properties,
 *       otherwise falls back to {@link InMemoryArtifactService}.
 *   <li><b>Builder-based (recommended)</b>: use {@link #newBuilder()} or the static factory
 *       methods {@link #create(BaseAgent, Firestore)} / {@link #create(BaseAgent, String,
 *       Firestore)} for full control over services, artifact storage, plugins, and runtime
 *       settings.
 * </ul>
 *
 * <p>When neither an explicit {@link BaseArtifactService} nor a GCS bucket (via {@link
 * Builder#withGcsArtifacts(String)} or {@link Builder#withGcsArtifacts(String, Storage)}) is
 * configured, the runner defaults to {@link InMemoryArtifactService}, allowing text-based
 * interactions without any GCS bucket or properties file setup.
 */
public class FirestoreDatabaseRunner extends Runner {

  // -------------------------------------------------------------------------
  // Legacy constructor-based API
  // -------------------------------------------------------------------------

  /** Creates a runner with Firestore services. The GCS bucket is read from properties. */
  public FirestoreDatabaseRunner(BaseAgent baseAgent, Firestore firestore) {
    this(baseAgent, baseAgent.name(), new ArrayList<>(), firestore);
  }

  /** Creates a runner with Firestore services and a custom app name. */
  public FirestoreDatabaseRunner(BaseAgent baseAgent, String appName, Firestore firestore) {
    this(baseAgent, appName, new ArrayList<>(), firestore);
  }

  /** Creates a runner with Firestore services, a custom app name, and plugins. */
  @SuppressWarnings("deprecation")
  public FirestoreDatabaseRunner(
      BaseAgent baseAgent,
      String appName,
      List<BasePlugin> plugins,
      Firestore firestore) {
    super(
        baseAgent,
        appName,
        resolveDefaultArtifactService(),
        new FirestoreSessionService(firestore),
        new FirestoreMemoryService(firestore),
        plugins);
  }

  /** Resolves the default artifact service based on available configuration. */
  private static BaseArtifactService resolveDefaultArtifactService() {
    String bucketName = findBucketNameFromProperties(); // safe lookup returning null if missing
    if (bucketName != null && !bucketName.trim().isEmpty()) {
      return new GcsArtifactService(
          bucketName, StorageOptions.getDefaultInstance().getService());
    }
    // If no bucket is configured, fall back to in-memory artifact service, this also default ADK behavior.
    return new InMemoryArtifactService();
  }

  /** Return the GCS bucket name from properties, or null if not set. */
  private static @Nullable String findBucketNameFromProperties() {
    String bucketName = FirestoreProperties.getInstance().getGcsAdkBucketName();
    return (bucketName == null || bucketName.trim().isEmpty()) ? null : bucketName.trim();
  }

  // -------------------------------------------------------------------------
  // Builder-based API
  // -------------------------------------------------------------------------

  /** Returns a new {@link Builder} to configure and build a {@link Runner}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Convenience factory: creates a {@link Runner} for the given agent and Firestore instance. The
   * GCS bucket is resolved from {@code gcs.adk.bucket.name} property or throws if not configured.
   */
  public static Runner create(BaseAgent agent, Firestore firestore) {
    return newBuilder().agent(agent).firestore(firestore).build();
  }

  /**
   * Convenience factory: creates a {@link Runner} for the given agent, app name, and Firestore
   * instance.
   */
  public static Runner create(BaseAgent agent, String appName, Firestore firestore) {
    return newBuilder().agent(agent).appName(appName).firestore(firestore).build();
  }

  /** Builder for configuring and creating a {@link Runner} backed by Firestore. */
  public static class Builder {
    private @Nullable BaseAgent agent;
    private @Nullable App app;
    private @Nullable String appName;
    private final List<Plugin> plugins = new ArrayList<>();

    private @Nullable Firestore firestore;
    private @Nullable BaseSessionService sessionService;
    private @Nullable BaseMemoryService memoryService;
    private @Nullable BaseArtifactService artifactService;

    private @Nullable String gcsBucketName;
    private @Nullable Storage storage;

    private InstantSource instantSource = InstantSource.system();
    private UuidProvider uuidProvider = UuidProvider.SYSTEM;
    private @Nullable Scheduler scheduler;

    private Builder() {}

    /** Sets the agent to run. Mutually exclusive with {@link #app(App)}. */
    @CanIgnoreReturnValue
    public Builder agent(BaseAgent agent) {
      Preconditions.checkState(this.app == null, "Cannot set agent when app is already set.");
      this.agent = agent;
      return this;
    }

    /** Sets the app to run. Mutually exclusive with {@link #agent(BaseAgent)}. */
    @CanIgnoreReturnValue
    public Builder app(App app) {
      Preconditions.checkState(this.agent == null, "Cannot set app when agent is already set.");
      this.app = app;
      return this;
    }

    /** Sets the application name. Defaults to agent or app name if not provided. */
    @CanIgnoreReturnValue
    public Builder appName(String appName) {
      this.appName = appName;
      return this;
    }

    /** Sets the Firestore client instance used for session and memory services. */
    @CanIgnoreReturnValue
    public Builder firestore(Firestore firestore) {
      this.firestore = firestore;
      return this;
    }

    /** Explicitly provides a custom {@link BaseSessionService}. */
    @CanIgnoreReturnValue
    public Builder sessionService(BaseSessionService sessionService) {
      this.sessionService = sessionService;
      return this;
    }

    /** Explicitly provides a custom {@link BaseMemoryService}. */
    @CanIgnoreReturnValue
    public Builder memoryService(BaseMemoryService memoryService) {
      this.memoryService = memoryService;
      return this;
    }

    /** Explicitly provides an artifact service implementation. */
    @CanIgnoreReturnValue
    public Builder artifactService(BaseArtifactService artifactService) {
      this.artifactService = artifactService;
      return this;
    }

    /** Configures GCS artifact storage with a bucket name and default storage options. */
    @CanIgnoreReturnValue
    public Builder withGcsArtifacts(String bucketName) {
      return withGcsArtifacts(bucketName, null);
    }

    /** Configures GCS artifact storage with a bucket name and explicit {@link Storage} client. */
    @CanIgnoreReturnValue
    public Builder withGcsArtifacts(String bucketName, @Nullable Storage storage) {
      this.gcsBucketName = bucketName;
      this.storage = storage;
      return this;
    }

    /** Adds plugins to the runner. */
    @CanIgnoreReturnValue
    public Builder plugins(List<? extends Plugin> plugins) {
      this.plugins.addAll(plugins);
      return this;
    }

    /** Adds plugins to the runner. */
    @CanIgnoreReturnValue
    public Builder plugins(Plugin... plugins) {
      this.plugins.addAll(ImmutableList.copyOf(plugins));
      return this;
    }

    /** Sets the {@link InstantSource} for timestamp generation. */
    @CanIgnoreReturnValue
    public Builder instantSource(InstantSource instantSource) {
      this.instantSource = Preconditions.checkNotNull(instantSource);
      return this;
    }

    /** Sets the {@link UuidProvider} for ID generation. */
    @CanIgnoreReturnValue
    public Builder uuidProvider(UuidProvider uuidProvider) {
      this.uuidProvider = Preconditions.checkNotNull(uuidProvider);
      return this;
    }

    /** Sets the {@link Scheduler} for hand-offs to worker threads. */
    @CanIgnoreReturnValue
    public Builder scheduler(@Nullable Scheduler scheduler) {
      this.scheduler = scheduler;
      return this;
    }

    /**
     * Converts this builder into a standard {@link Runner.Builder}, pre-configured with Firestore
     * services and artifact service. Callers can use this to apply any additional runner settings.
     */
    public Runner.Builder toRunnerBuilder() {
      if (this.app == null && this.agent == null) {
        throw new IllegalStateException("Either agent or app must be set.");
      }

      String resolvedAppName = this.appName;
      if (resolvedAppName == null) {
        resolvedAppName = (this.app != null) ? this.app.name() : this.agent.name();
      }

      BaseSessionService resolvedSessionService = this.sessionService;
      BaseMemoryService resolvedMemoryService = this.memoryService;

      if (resolvedSessionService == null || resolvedMemoryService == null) {
        if (this.firestore == null) {
          throw new IllegalStateException(
              "Firestore client must be provided when session or memory service is not explicitly"
                  + " configured.");
        }
        if (resolvedSessionService == null) {
          resolvedSessionService =
              new FirestoreSessionService(this.firestore, this.instantSource, this.uuidProvider);
        }
        if (resolvedMemoryService == null) {
          resolvedMemoryService = new FirestoreMemoryService(this.firestore);
        }
      }

      BaseArtifactService resolvedArtifactService = this.artifactService;
      if (resolvedArtifactService == null) {
        String bucket = this.gcsBucketName;
        if (bucket == null || bucket.trim().isEmpty()) {
          bucket = FirestoreProperties.getInstance().getGcsAdkBucketName();
        }

        if (bucket != null && !bucket.trim().isEmpty()) {
          Storage storageClient =
              this.storage != null ? this.storage : StorageOptions.getDefaultInstance().getService();
          resolvedArtifactService = new GcsArtifactService(bucket, storageClient);
        } else {
          resolvedArtifactService = new InMemoryArtifactService();
        }
      }

      Runner.Builder runnerBuilder = Runner.builder();
      if (this.app != null) {
        runnerBuilder.app(this.app);
      } else {
        runnerBuilder.agent(this.agent).appName(resolvedAppName);
      }

      runnerBuilder
          .sessionService(resolvedSessionService)
          .memoryService(resolvedMemoryService)
          .artifactService(resolvedArtifactService)
          .instantSource(this.instantSource)
          .uuidProvider(this.uuidProvider);

      if (!this.plugins.isEmpty()) {
        runnerBuilder.plugins(this.plugins);
      }
      if (this.scheduler != null) {
        runnerBuilder.scheduler(this.scheduler);
      }

      return runnerBuilder;
    }

    /** Builds the configured {@link Runner}. */
    public Runner build() {
      return toRunnerBuilder().build();
    }
  }
}
