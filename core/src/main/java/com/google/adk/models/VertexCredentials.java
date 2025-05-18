package com.google.adk.models;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auto.value.AutoValue;
import java.util.Optional;
import javax.annotation.Nullable;

/** Credentials for accessing Gemini models through Vertex. */
@AutoValue
public abstract class VertexCredentials {

  public abstract Optional<String> project();

  public abstract Optional<String> location();

  public abstract Optional<GoogleCredentials> credentials();

  public static Builder builder() {
    return new AutoValue_VertexCredentials.Builder();
  }

  /** Builder for {@link VertexCredentials}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setProject(Optional<String> value);

    public abstract Builder setProject(@Nullable String value);

    public abstract Builder setLocation(Optional<String> value);

    public abstract Builder setLocation(@Nullable String value);

    public abstract Builder setCredentials(Optional<GoogleCredentials> value);

    public abstract Builder setCredentials(@Nullable GoogleCredentials value);

    public abstract VertexCredentials build();
  }
}
