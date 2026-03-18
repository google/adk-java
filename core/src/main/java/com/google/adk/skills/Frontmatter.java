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

package com.google.adk.skills;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** L1 skill content: metadata parsed from SKILL.md for skill discovery. */
@AutoValue
@JsonDeserialize(builder = Frontmatter.Builder.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Frontmatter extends JsonBaseModel {

  private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

  /** Skill name in kebab-case. */
  @JsonProperty("name")
  public abstract String name();

  /** What the skill does and when the model should use it. */
  @JsonProperty("description")
  public abstract String description();

  /** License for the skill. */
  @JsonProperty("license")
  public abstract Optional<String> license();

  /** Compatibility information for the skill. */
  @JsonProperty("compatibility")
  public abstract Optional<String> compatibility();

  /** A space-delimited list of tools that are pre-approved to run. */
  @JsonProperty("allowedTools")
  public abstract Optional<String> allowedTools();

  /** Key-value pairs for client-specific properties. */
  @JsonProperty("metadata")
  public abstract ImmutableMap<String, Object> metadata();

  public String toXml() {
    Escaper escaper = HtmlEscapers.htmlEscaper();
    return String.format(
        """
        <skill>
        <name>
        %s
        </name>
        <description>
        %s
        </description>
        </skill>
        """,
        escaper.escape(name()), escaper.escape(description()));
  }

  public static Builder builder() {
    return new AutoValue_Frontmatter.Builder().metadata(ImmutableMap.of());
  }

  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    private static Builder create() {
      return builder();
    }

    @CanIgnoreReturnValue
    @JsonProperty("name")
    public abstract Builder name(String name);

    @CanIgnoreReturnValue
    @JsonProperty("description")
    public abstract Builder description(String description);

    @CanIgnoreReturnValue
    @JsonProperty("license")
    public abstract Builder license(String license);

    @CanIgnoreReturnValue
    @JsonProperty("compatibility")
    public abstract Builder compatibility(String compatibility);

    @CanIgnoreReturnValue
    @JsonProperty("allowed-tools")
    @JsonAlias({"allowed_tools"})
    public abstract Builder allowedTools(String allowedTools);

    @CanIgnoreReturnValue
    @JsonProperty("metadata")
    public abstract Builder metadata(Map<String, Object> metadata);

    abstract Frontmatter autoBuild();

    public Frontmatter build() {
      Frontmatter fm = autoBuild();
      if (fm.name().length() > 64) {
        throw new IllegalArgumentException("name must be at most 64 characters");
      }
      if (!NAME_PATTERN.matcher(fm.name()).matches()) {
        throw new IllegalArgumentException(
            "name must be lowercase kebab-case (a-z, 0-9, hyphens), with no leading, trailing, or"
                + " consecutive hyphens");
      }
      if (fm.description().isEmpty()) {
        throw new IllegalArgumentException("description must not be empty");
      }
      if (fm.description().length() > 1024) {
        throw new IllegalArgumentException("description must be at most 1024 characters");
      }
      if (fm.compatibility().isPresent() && fm.compatibility().get().length() > 500) {
        throw new IllegalArgumentException("compatibility must be at most 500 characters");
      }
      return fm;
    }
  }
}
