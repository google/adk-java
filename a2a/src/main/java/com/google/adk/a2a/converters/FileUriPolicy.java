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
package com.google.adk.a2a.converters;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Decides which file URIs received over A2A may reach the model.
 *
 * <p>A model backend fetches a file URI with the agent's own credentials, so a URI chosen by a
 * caller or a remote agent can read anything the agent can. {@link #denyAll()} admits none.
 */
public final class FileUriPolicy {

  /** What to do with a file part whose URI the policy does not admit. */
  public enum DisallowedAction {
    /** Replace the part with a text note saying a file reference was not loaded. */
    PLACEHOLDER,
    /** Remove the part. */
    DROP,
    /** Fail the conversion with {@link DisallowedFileUriException}. */
    REJECT
  }

  /** A file reference received over A2A, as seen by a policy predicate. */
  public static final class FileUriContext {
    private final String uri;
    private final @Nullable String mimeType;
    private final @Nullable String name;

    private FileUriContext(String uri, @Nullable String mimeType, @Nullable String name) {
      this.uri = checkNotNull(uri, "uri");
      this.mimeType = mimeType;
      this.name = name;
    }

    public static FileUriContext of(String uri, @Nullable String mimeType, @Nullable String name) {
      return new FileUriContext(uri, mimeType, name);
    }

    public String uri() {
      return uri;
    }

    public @Nullable String mimeType() {
      return mimeType;
    }

    public @Nullable String name() {
      return name;
    }
  }

  // The schemes model backends fetch; allowAll() admits no others.
  private static final ImmutableSet<String> ALLOW_ALL_SCHEMES =
      ImmutableSet.of("gs", "http", "https");
  // Never fetched for a model, whichever policy is set.
  private static final ImmutableSet<String> DENIED_SCHEMES =
      ImmutableSet.of("file", "data", "javascript", "ftp", "jar");
  // Loopback aliases, every name the GCE metadata server answers to, and the EC2 metadata names.
  private static final ImmutableSet<String> DENIED_HOSTS =
      ImmutableSet.of(
          "localhost",
          "localhost.localdomain",
          "ip6-localhost",
          "ip6-loopback",
          "metadata",
          "metadata.goog",
          "metadata.google",
          "metadata.google.internal",
          "instance-data",
          "instance-data.ec2.internal");
  private static final ImmutableList<String> DENIED_ENCODINGS =
      ImmutableList.of("%2e", "%2f", "%5c");
  // An HTTP server reads an encoded "/" as part of a segment name, as in Firebase Storage links
  // (.../o/dir%2Ffile.png); dot segments are still checked once the path is decoded.
  private static final ImmutableList<String> DENIED_HTTP_ENCODINGS = ImmutableList.of("%2e", "%5c");
  private static final Pattern FILES_API_NAME = Pattern.compile("files/[a-z0-9-]{1,64}");
  private static final Pattern GCS_BUCKET = Pattern.compile("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]");
  private static final Pattern HOST_AND_PORT = Pattern.compile("([a-z0-9._-]+)(?::[0-9]{1,5})?");
  // ASCII characters java.net.URI rejects in a path, query or fragment. Non-ASCII is allowed there.
  private static final CharMatcher ILLEGAL_PATH_CHARS = CharMatcher.anyOf(" \"<>[]^`{|}");
  // A GCS object name is the whole string after the bucket, so "?" and "#" are part of it.
  private static final CharMatcher SEGMENT_SUFFIX_START = CharMatcher.anyOf(";?#");
  private static final CharMatcher GCS_LITERAL_CHARS =
      ILLEGAL_PATH_CHARS.or(CharMatcher.anyOf("?#"));
  private static final CharMatcher HEX_DIGITS =
      CharMatcher.inRange('0', '9')
          .or(CharMatcher.inRange('a', 'f'))
          .or(CharMatcher.inRange('A', 'F'));
  private static final String HEX = "0123456789ABCDEF";

  private static final FileUriPolicy DENY_ALL =
      new FileUriPolicy(false, ImmutableList.of(), null, DisallowedAction.PLACEHOLDER);
  private static final FileUriPolicy ALLOW_ALL =
      new FileUriPolicy(true, ImmutableList.of(), null, DisallowedAction.PLACEHOLDER);

  private final boolean allowAll;
  private final ImmutableList<URI> allowedPrefixes;
  private final @Nullable Predicate<FileUriContext> predicate;
  private final DisallowedAction onDisallowed;

  private FileUriPolicy(
      boolean allowAll,
      ImmutableList<URI> allowedPrefixes,
      @Nullable Predicate<FileUriContext> predicate,
      DisallowedAction onDisallowed) {
    this.allowAll = allowAll;
    this.allowedPrefixes = allowedPrefixes;
    this.predicate = predicate;
    this.onDisallowed = onDisallowed;
  }

  /**
   * The policy converters apply when none is configured. It currently admits what {@link
   * #allowAll()} admits.
   */
  public static FileUriPolicy defaultPolicy() {
    return ALLOW_ALL;
  }

  /** Admits no file URI and replaces each one with a placeholder. */
  public static FileUriPolicy denyAll() {
    return DENY_ALL;
  }

  /**
   * Admits {@code gs}, {@code http} and {@code https} URIs and Gemini Files API names, except those
   * that are never safe: loopback and IP-literal hosts, the cloud metadata server, and paths that
   * climb out of their directory. Other schemes need a prefix or a predicate.
   *
   * <p>Use this only when every A2A caller and remote agent is trusted with the agent's access.
   */
  public static FileUriPolicy allowAll() {
    return ALLOW_ALL;
  }

  /** Admits file URIs under any of {@code prefixes}; see {@link Builder#allowPrefix(String)}. */
  public static FileUriPolicy allowPrefixes(String... prefixes) {
    Builder builder = builder();
    for (String prefix : prefixes) {
      builder.allowPrefix(prefix);
    }
    return builder.build();
  }

  /** Returns a builder for a policy that admits nothing until prefixes or a predicate are added. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Whether the file reference in {@code context} may be passed to the model.
   *
   * <p>A Gemini Files API name such as {@code files/abc-123} has no scheme or authority, so only
   * {@link #allowAll()} or a predicate can admit it.
   */
  public boolean isAllowed(FileUriContext context) {
    if (FILES_API_NAME.matcher(context.uri()).matches()) {
      return allowAll || (predicate != null && predicate.test(context));
    }
    Optional<URI> parsed = parseSafe(context.uri());
    if (parsed.isEmpty()) {
      return false;
    }
    URI uri = parsed.get();
    if (allowAll) {
      return ALLOW_ALL_SCHEMES.contains(Ascii.toLowerCase(uri.getScheme()));
    }
    for (URI prefix : allowedPrefixes) {
      if (isUnder(uri, prefix)) {
        return true;
      }
    }
    return predicate != null && predicate.test(context);
  }

  /** What converters do with a file part whose URI this policy does not admit. */
  public DisallowedAction onDisallowed() {
    return onDisallowed;
  }

  private static boolean isUnder(URI uri, URI prefix) {
    return Ascii.equalsIgnoreCase(prefix.getScheme(), uri.getScheme())
        && Ascii.equalsIgnoreCase(prefix.getRawAuthority(), uri.getRawAuthority())
        && uri.getRawPath().startsWith(prefix.getRawPath());
  }

  /** Parses {@code raw}, or returns empty when it is malformed or never safe to fetch. */
  private static Optional<URI> parseSafe(@Nullable String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = new URI(encodeIllegalPathChars(raw));
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
    String scheme = uri.getScheme();
    String authority = uri.getRawAuthority();
    if (scheme == null
        || uri.isOpaque()
        || DENIED_SCHEMES.contains(Ascii.toLowerCase(scheme))
        || authority == null
        || isDeniedAuthority(Ascii.toLowerCase(scheme), authority)
        || isDeniedPath(Ascii.toLowerCase(scheme), uri)) {
      return Optional.empty();
    }
    return Optional.of(uri);
  }

  /**
   * Percent-encodes characters that {@link URI} rejects after the authority, such as the spaces in
   * {@code gs://bucket/my report.pdf}, which ADK's own GCS artifact links contain. For {@code
   * gs://} it also encodes {@code ?} and {@code #}, so the path checks see the whole object name
   * rather than stopping at what would otherwise parse as a query or fragment. Characters before
   * the authority ends are left alone, so the authority checks still see them.
   */
  private static String encodeIllegalPathChars(String raw) {
    boolean isGcs = Ascii.toLowerCase(raw).startsWith("gs://");
    CharMatcher toEncode = isGcs ? GCS_LITERAL_CHARS : ILLEGAL_PATH_CHARS;
    // Without "://" there is no authority, and parseSafe denies the URI whatever this returns.
    int authorityStart = raw.indexOf("://") + 3;
    int pathStart =
        isGcs
            ? CharMatcher.anyOf("/?#").indexIn(raw, authorityStart)
            : raw.indexOf('/', authorityStart);
    if (pathStart == -1) {
      return raw;
    }
    StringBuilder encoded = new StringBuilder(raw.substring(0, pathStart));
    for (int i = pathStart; i < raw.length(); i++) {
      char c = raw.charAt(i);
      boolean isEscape =
          c == '%'
              && i + 2 < raw.length()
              && HEX_DIGITS.matches(raw.charAt(i + 1))
              && HEX_DIGITS.matches(raw.charAt(i + 2));
      if (toEncode.matches(c) || (c == '%' && !isEscape)) {
        encoded.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xf));
      } else {
        encoded.append(c);
      }
    }
    return encoded.toString();
  }

  private static boolean isDeniedAuthority(String scheme, String authority) {
    // Both patterns below admit only ASCII letters, digits and ".-_", so user-info ("u:p@"),
    // percent escapes and non-ASCII characters, which can spell a denied host in a form the checks
    // miss (such as %6c%6f%63%61%6c%68%6f%73%74 or an ideographic full stop), never pass.
    String host = Ascii.toLowerCase(authority);
    if (scheme.equals("gs")) {
      // A bucket name, not a network host; it may be all digits.
      return !GCS_BUCKET.matcher(host).matches();
    }
    // A host with at most a numeric port; this also rejects bracketed and bare IPv6 literals.
    Matcher hostAndPort = HOST_AND_PORT.matcher(host);
    if (!hostAndPort.matches()) {
      return true;
    }
    host = hostAndPort.group(1);
    while (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (host.isEmpty() || DENIED_HOSTS.contains(host) || host.endsWith(".localhost")) {
      return true;
    }
    // No registered top-level domain is numeric, so a numeric or hex last label is an IP address
    // in one of its many spellings (127.0.0.1, 2130706433, 0x7f.1).
    String lastLabel = host.substring(host.lastIndexOf('.') + 1);
    return lastLabel.chars().allMatch(Character::isDigit) || lastLabel.startsWith("0x");
  }

  private static boolean isDeniedPath(String scheme, URI uri) {
    String rawPath = uri.getRawPath();
    if (rawPath == null || rawPath.contains("//")) {
      return true;
    }
    String lowerRawPath = Ascii.toLowerCase(rawPath);
    ImmutableList<String> deniedRawEncodings =
        scheme.equals("http") || scheme.equals("https") ? DENIED_HTTP_ENCODINGS : DENIED_ENCODINGS;
    if (deniedRawEncodings.stream().anyMatch(lowerRawPath::contains)) {
      return true;
    }
    String path = uri.getPath();
    if (path.indexOf('\\') >= 0 || path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      return true;
    }
    // A fetcher that decodes again would turn %252e%252e or %2%65%2%65 into a traversal.
    String lowerPath = Ascii.toLowerCase(path);
    if (DENIED_ENCODINGS.stream().anyMatch(lowerPath::contains)) {
      return true;
    }
    for (String segment : Splitter.on('/').split(path)) {
      // Servlet containers drop ";params" from a segment, so "..;x" also climbs; so does "..?x" in
      // a
      // gs:// object name once a fetcher maps it to an HTTP URL.
      int end = SEGMENT_SUFFIX_START.indexIn(segment);
      String name = end >= 0 ? segment.substring(0, end) : segment;
      if (name.equals(".") || name.equals("..")) {
        return true;
      }
    }
    return false;
  }

  /** Builder for {@link FileUriPolicy}. */
  public static final class Builder {
    private final ImmutableList.Builder<URI> allowedPrefixes = ImmutableList.builder();
    private @Nullable Predicate<FileUriContext> predicate;
    private DisallowedAction onDisallowed = DisallowedAction.PLACEHOLDER;

    private Builder() {}

    /**
     * Admits file URIs under {@code prefix}, such as {@code gs://my-bucket/inbox/}.
     *
     * <p>The prefix needs a scheme and an authority (a bucket or host) and must end in {@code /},
     * so that {@code gs://b/a/} admits neither {@code gs://b/a-other/} nor {@code gs://b-other/a/}.
     * Paths are compared percent-encoded, so {@code gs://b/my dir/} and {@code gs://b/my%20dir/}
     * are the same prefix.
     *
     * @throws IllegalArgumentException if the prefix is malformed or never safe to fetch
     */
    @CanIgnoreReturnValue
    public Builder allowPrefix(String prefix) {
      Optional<URI> parsed = parseSafe(prefix);
      checkArgument(
          parsed.isPresent()
              && parsed.get().getRawQuery() == null
              && parsed.get().getRawFragment() == null
              && parsed.get().getRawPath().endsWith("/"),
          "Allowed file URI prefix must be an absolute URI with an authority, no query or fragment,"
              + " and a path ending in '/'");
      allowedPrefixes.add(parsed.get());
      return this;
    }

    /** Also admits file URIs for which {@code predicate} returns true. */
    @CanIgnoreReturnValue
    public Builder predicate(Predicate<FileUriContext> predicate) {
      this.predicate = checkNotNull(predicate, "predicate");
      return this;
    }

    /** Sets what to do with a file part the policy does not admit; defaults to PLACEHOLDER. */
    @CanIgnoreReturnValue
    public Builder onDisallowed(DisallowedAction onDisallowed) {
      this.onDisallowed = checkNotNull(onDisallowed, "onDisallowed");
      return this;
    }

    public FileUriPolicy build() {
      return new FileUriPolicy(false, allowedPrefixes.build(), predicate, onDisallowed);
    }
  }
}
