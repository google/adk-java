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

package com.google.adk.models;

import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.stream.Collectors.joining;

import com.google.adk.Version;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import org.jspecify.annotations.Nullable;

/**
 * Headers that let Google APIs attribute model calls to ADK, in the format all ADK SDKs use. A
 * header value is a space-separated list of {@code name/version} tags.
 */
final class TrackingHeaders {

  private static final Splitter TAG_SPLITTER = Splitter.on(' ').omitEmptyStrings();

  /** ADK's own tag, without a hosting runtime suffix. */
  static final String ADK_TAG = "google-adk/" + Version.JAVA_ADK_VERSION;

  /** The names of the tracking headers, in lower case. */
  static final ImmutableSet<String> HEADER_NAMES =
      ImmutableSet.of("x-goog-api-client", "user-agent");

  /** The authority of an https URL as written: up to the path, query or fragment. */
  private static final Pattern RAW_AUTHORITY = Pattern.compile("(?i)https://([^/?#]+)");

  /**
   * Returns ADK's tag, with the hosting runtime as a build-metadata suffix if {@code
   * includeRuntimeTag}, for example {@code google-adk/1.0.0+cloud_run}.
   */
  private static String adkTag(boolean includeRuntimeTag) {
    String runtimeTag = includeRuntimeTag ? runtimeTag(System::getenv) : null;
    return runtimeTag == null ? ADK_TAG : ADK_TAG + "+" + runtimeTag;
  }

  /**
   * Returns {@link #adkTag} followed by the Java version tag, for clients that send no language
   * tag.
   */
  static String value(boolean includeRuntimeTag) {
    return adkTag(includeRuntimeTag) + " gl-java/" + JAVA_VERSION.value();
  }

  /**
   * Returns both tracking headers set to {@link #adkTag}, for a genai SDK client, which puts its
   * own {@code gl-java} tag first.
   */
  static ImmutableMap<String, String> genAiSdkHeaders(boolean includeRuntimeTag) {
    String tag = adkTag(includeRuntimeTag);
    return ImmutableMap.of("x-goog-api-client", tag, "user-agent", tag);
  }

  /**
   * Returns the hosting runtime tag, or null when none is detected. Only checks whether variables
   * the platform sets are non-empty; their values are never sent.
   */
  static @Nullable String runtimeTag(Function<String, @Nullable String> getenv) {
    // Most specific first: Agent Engine may also set K_*, Cloud Run on GKE KUBERNETES_SERVICE_HOST.
    if (isSet(getenv, "GOOGLE_CLOUD_AGENT_ENGINE_ID")) {
      return "remote_reasoning_engine";
    }
    if (isSet(getenv, "K_SERVICE") || isSet(getenv, "CLOUD_RUN_JOB")) {
      return "cloud_run";
    }
    if (isSet(getenv, "KUBERNETES_SERVICE_HOST")) {
      return "k8s";
    }
    return null;
  }

  private static boolean isSet(Function<String, @Nullable String> getenv, String name) {
    return !Strings.isNullOrEmpty(getenv.apply(name));
  }

  /**
   * Returns whether {@code url} is an https {@code *.googleapis.com} URL. A user info or backslash
   * in the authority is rejected, because HTTP clients disagree on which host such a URL names.
   */
  static boolean isGoogleApisUrl(@Nullable String url) {
    HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
    if (parsed == null || !parsed.isHttps() || !parsed.host().endsWith(".googleapis.com")) {
      return false;
    }
    // HttpUrl drops an empty user info and extra slashes and reads a backslash as a slash.
    Matcher authority = RAW_AUTHORITY.matcher(url);
    return authority.lookingAt() && CharMatcher.anyOf("@\\").matchesNoneOf(authority.group(1));
  }

  /**
   * Like {@link #mergeInto}, but adds only {@link #adkTag}, for headers that the genai SDK appends
   * to its own label.
   */
  static ImmutableMap<String, String> mergeIntoGenAiSdkHeaders(
      Map<String, String> headers, boolean includeRuntimeTag) {
    return mergeInto(headers, adkTag(includeRuntimeTag));
  }

  /**
   * Returns {@code headers} with each tracking header, matched case-insensitively, followed by
   * every tag of {@link #value} whose name it lacks.
   */
  static ImmutableMap<String, String> mergeInto(
      Map<String, String> headers, boolean includeRuntimeTag) {
    return mergeInto(headers, value(includeRuntimeTag));
  }

  private static ImmutableMap<String, String> mergeInto(
      Map<String, String> headers, String adkTags) {
    Map<String, String> merged = new LinkedHashMap<>();
    Map<String, String> callerValues = new LinkedHashMap<>();
    headers.forEach((name, value) -> {
      String trackingName = Ascii.toLowerCase(name);
      if (HEADER_NAMES.contains(trackingName)) {
        callerValues.merge(trackingName, value, (first, second) -> first + " " + second);
      } else {
        merged.put(name, value);
      }
    });
    for (String name : HEADER_NAMES) {
      merged.put(name, extend(callerValues.getOrDefault(name, ""), adkTags));
    }
    return ImmutableMap.copyOf(merged);
  }

  /** Returns {@code callerValue} followed by each tag of {@code adkTags} whose name it lacks. */
  private static String extend(String callerValue, String adkTags) {
    List<String> tags = TAG_SPLITTER.splitToList(callerValue);
    ImmutableSet<String> names =
        tags.stream().map(TrackingHeaders::tagName).collect(toImmutableSet());
    return Stream.concat(
            tags.stream(),
            TAG_SPLITTER.splitToStream(adkTags).filter(tag -> !names.contains(tagName(tag))))
        .collect(joining(" "));
  }

  private static String tagName(String tag) {
    int slash = tag.indexOf('/');
    return slash < 0 ? tag : tag.substring(0, slash);
  }

  private TrackingHeaders() {}
}
