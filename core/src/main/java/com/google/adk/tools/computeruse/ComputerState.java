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

package com.google.adk.tools.computeruse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents the current state of the computer environment.
 *
 * <p>Attributes: screenshot: The screenshot in PNG format as bytes. url: The current URL of the
 * webpage being displayed.
 */
public final class ComputerState {
  private final byte[] screenshot;
  private final Optional<String> url;

  @JsonCreator
  private ComputerState(
      @JsonProperty("screenshot") byte[] screenshot, @JsonProperty("url") Optional<String> url) {
    this.screenshot = screenshot.clone();
    this.url = url;
  }

  @JsonProperty("screenshot")
  public byte[] screenshot() {
    return screenshot.clone();
  }

  @JsonProperty("url")
  public Optional<String> url() {
    return url;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ComputerState}. */
  public static final class Builder {
    private byte[] screenshot;
    private Optional<String> url = Optional.empty();

    @CanIgnoreReturnValue
    public Builder screenshot(byte[] screenshot) {
      this.screenshot = screenshot.clone();
      return this;
    }

    @CanIgnoreReturnValue
    public Builder url(Optional<String> url) {
      this.url = url;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder url(String url) {
      this.url = Optional.ofNullable(url);
      return this;
    }

    public ComputerState build() {
      return new ComputerState(screenshot, url);
    }
  }

  public static ComputerState create(byte[] screenshot, String url) {
    return builder().screenshot(screenshot).url(url).build();
  }

  public static ComputerState create(byte[] screenshot) {
    return builder().screenshot(screenshot).build();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ComputerState that)) {
      return false;
    }
    return Objects.deepEquals(screenshot, that.screenshot) && Objects.equals(url, that.url);
  }

  @Override
  public int hashCode() {
    return Objects.hash(Arrays.hashCode(screenshot), url);
  }
}
