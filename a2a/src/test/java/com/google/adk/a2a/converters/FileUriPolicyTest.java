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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.adk.a2a.converters.FileUriPolicy.DisallowedAction;
import com.google.adk.a2a.converters.FileUriPolicy.FileUriContext;
import com.google.common.collect.ImmutableList;
import java.util.Objects;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class FileUriPolicyTest {

  @Test
  public void denyAll_deniesEveryUri() {
    FileUriPolicy policy = FileUriPolicy.denyAll();

    assertThat(policy.isAllowed(context("gs://bucket/f"))).isFalse();
    assertThat(policy.isAllowed(context("https://example.com/f.png"))).isFalse();
    assertThat(policy.onDisallowed()).isEqualTo(DisallowedAction.PLACEHOLDER);
  }

  @Test
  public void defaultPolicy_admitsWhatAllowAllAdmits() {
    FileUriPolicy policy = FileUriPolicy.defaultPolicy();

    assertThat(policy.isAllowed(context("gs://bucket/f"))).isTrue();
    assertThat(policy.isAllowed(context("https://example.com/f.png"))).isTrue();
    assertThat(policy.isAllowed(context("http://metadata.google.internal/x"))).isFalse();
    assertThat(policy.onDisallowed()).isEqualTo(DisallowedAction.PLACEHOLDER);
  }

  @Test
  public void allowAll_deniesOtherSchemes() {
    FileUriPolicy policy = FileUriPolicy.allowAll();

    for (String uri :
        ImmutableList.of(
            "ftps://h.example.com/x",
            "sftp://h.example.com/x",
            "gopher://h.example.com/x",
            "dict://h.example.com:2628/x",
            "ldap://h.example.com/x",
            "smb://h.example.com/x",
            "blobstore://test/file.pdf")) {
      assertWithMessage(uri).that(policy.isAllowed(context(uri))).isFalse();
    }
    assertThat(policy.isAllowed(context("HTTP://example.com/f"))).isTrue();
  }

  @Test
  public void predicateOrPrefix_canAdmitOtherSchemes() {
    assertThat(
            FileUriPolicy.builder()
                .predicate(c -> c.uri().startsWith("blobstore://"))
                .build()
                .isAllowed(context("blobstore://test/file.pdf")))
        .isTrue();
    assertThat(
            FileUriPolicy.allowPrefixes("blobstore://test/")
                .isAllowed(context("blobstore://test/file.pdf")))
        .isTrue();
  }

  @Test
  public void allowAll_admitsOrdinaryUris() {
    FileUriPolicy policy = FileUriPolicy.allowAll();

    assertThat(policy.isAllowed(context("gs://bucket/path/f.pdf"))).isTrue();
    assertThat(policy.isAllowed(context("https://example.com/f.png?x=1"))).isTrue();
    assertThat(policy.isAllowed(context("https://example.com:8443/f.png"))).isTrue();
    assertThat(policy.isAllowed(context("gs://123456/f.pdf"))).isTrue();
    assertThat(policy.isAllowed(context("gs://0xbucket/f.pdf"))).isTrue();
    assertThat(policy.isAllowed(context("gs://my_bucket.example.com/f.pdf"))).isTrue();
  }

  @Test
  public void unencodedArtifactNames_areJudgedLikeEncodedOnes() {
    // GcsArtifactService links are "gs://" + bucket + "/" + blob name, with the name unencoded.
    FileUriPolicy prefixes = FileUriPolicy.allowPrefixes("gs://artifacts/app/user/");
    FileUriContext spaces = context("gs://artifacts/app/user/session/my report.pdf/0");
    FileUriContext percentAndBrackets =
        context("gs://artifacts/app/user/session/100% done [v2].txt/1");
    FileUriContext nonAsciiAndBraces =
        context("gs://artifacts/app/user/session/caf\u00e9 {d}.md/0");

    assertThat(FileUriPolicy.allowAll().isAllowed(spaces)).isTrue();
    assertThat(FileUriPolicy.allowAll().isAllowed(percentAndBrackets)).isTrue();
    assertThat(FileUriPolicy.allowAll().isAllowed(nonAsciiAndBraces)).isTrue();
    assertThat(prefixes.isAllowed(spaces)).isTrue();
    assertThat(prefixes.isAllowed(percentAndBrackets)).isTrue();
    assertThat(prefixes.isAllowed(nonAsciiAndBraces)).isTrue();
    assertThat(prefixes.isAllowed(context("gs://artifacts/app/other/s/my report.pdf/0"))).isFalse();
  }

  @Test
  public void unencodedPath_withShortHost_isEncodedFromTheFirstSlash() {
    // The host is shorter than the scheme, so the path's first slash comes soon after "://".
    FileUriContext shortHost = context("https://a.co/my report.pdf");

    assertThat(FileUriPolicy.allowAll().isAllowed(shortHost)).isTrue();
    assertThat(FileUriPolicy.allowPrefixes("https://a.co/").isAllowed(shortHost)).isTrue();
  }

  @Test
  public void allowPrefixes_admitsHttpPathWithEncodedSlash() {
    String firebaseLink =
        "https://firebasestorage.googleapis.com/v0/b/bkt/o/dir%2Ffile.png?alt=media";

    assertThat(
            FileUriPolicy.allowPrefixes("https://firebasestorage.googleapis.com/v0/b/bkt/o/")
                .isAllowed(context(firebaseLink)))
        .isTrue();
    assertThat(FileUriPolicy.allowAll().isAllowed(context(firebaseLink))).isTrue();
  }

  @Test
  public void allowPrefixes_readsGcsQuestionMarkAndHashAsPartOfObjectName() {
    FileUriPolicy policy = FileUriPolicy.allowPrefixes("gs://shared/inbox/");

    assertThat(policy.isAllowed(context("gs://shared/inbox/C# notes.md"))).isTrue();
    assertThat(policy.isAllowed(context("gs://shared/inbox/why?.txt"))).isTrue();
    assertThat(policy.isAllowed(context("gs://shared/inbox?/a"))).isFalse();
    assertThat(policy.isAllowed(context("gs://shared/in#box/a"))).isFalse();
  }

  @Test
  public void allowAll_admitsHostWithTrailingDot() {
    // A fully qualified name may end in "."; denied hosts stay denied with or without it.
    assertThat(FileUriPolicy.allowAll().isAllowed(context("https://cdn.example.com./f"))).isTrue();
    assertThat(FileUriPolicy.allowAll().isAllowed(context("http://localhost./f"))).isFalse();
  }

  @Test
  public void allowPrefixes_comparesHostCaseInAsciiOnly() {
    FileUriPolicy policy = FileUriPolicy.allowPrefixes("https://k.example.com/");

    assertThat(policy.isAllowed(context("https://K.EXAMPLE.COM/f"))).isTrue();
    assertThat(policy.isAllowed(context("https://\u212A.example.com/f"))).isFalse();
  }

  @Test
  public void filesApiName_isAdmittedOnlyByAllowAllOrPredicate() {
    FileUriContext filesApiName = context("files/abc-123");

    assertThat(FileUriPolicy.denyAll().isAllowed(filesApiName)).isFalse();
    assertThat(FileUriPolicy.allowPrefixes("gs://inbox/in/").isAllowed(filesApiName)).isFalse();
    assertThat(FileUriPolicy.allowAll().isAllowed(filesApiName)).isTrue();
    assertThat(
            FileUriPolicy.builder()
                .predicate(c -> c.uri().startsWith("files/"))
                .build()
                .isAllowed(filesApiName))
        .isTrue();
  }

  @Test
  public void allowPrefixes_admitsUrisUnderPrefix() {
    FileUriPolicy policy = FileUriPolicy.allowPrefixes("gs://shared/inbox/");

    assertThat(policy.isAllowed(context("gs://shared/inbox/f.pdf"))).isTrue();
    assertThat(policy.isAllowed(context("gs://shared/inbox/sub/f.pdf"))).isTrue();
    assertThat(policy.isAllowed(context("GS://SHARED/inbox/f.pdf"))).isTrue();
  }

  @Test
  public void allowPrefixes_deniesUrisOutsidePrefix() {
    FileUriPolicy policy = FileUriPolicy.allowPrefixes("gs://shared/inbox/");

    assertThat(policy.isAllowed(context("gs://shared/inbox-other/f.pdf"))).isFalse();
    assertThat(policy.isAllowed(context("gs://shared-other/inbox/f.pdf"))).isFalse();
    assertThat(policy.isAllowed(context("gs://shared/f.pdf"))).isFalse();
    assertThat(policy.isAllowed(context("gs://shared/INBOX/f.pdf"))).isFalse();
    assertThat(policy.isAllowed(context("https://shared/inbox/f.pdf"))).isFalse();
    assertThat(policy.isAllowed(context("gs://shared/inbox/../secret"))).isFalse();
    assertThat(policy.isAllowed(context("gs://shared/inbox/%2e%2e/secret"))).isFalse();
  }

  @Test
  public void allowPrefixes_matchesAnyOfSeveralPrefixes() {
    FileUriPolicy policy =
        FileUriPolicy.allowPrefixes("gs://inbox/in/", "https://cdn.example.com/");

    assertThat(policy.isAllowed(context("gs://inbox/in/f"))).isTrue();
    assertThat(policy.isAllowed(context("https://cdn.example.com/img.png"))).isTrue();
    assertThat(policy.isAllowed(context("https://cdn.example.com.evil.test/img.png"))).isFalse();
  }

  @Test
  public void allowPrefix_rejectsMalformedPrefixes() {
    FileUriPolicy.Builder builder = FileUriPolicy.builder();

    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix(null));
    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix(""));
    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix("gs://bucket/a\tb/"));

    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix("gs://bucket"));
    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix("gs://bucket/path"));
    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix("gs://"));
    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix("bucket/path/"));
    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix("file:///tmp/"));
    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix("http://127.0.0.1/"));
    assertThrows(
        IllegalArgumentException.class, () -> builder.allowPrefix("https://example.com/?q=1/"));
    assertThrows(IllegalArgumentException.class, () -> builder.allowPrefix("gs://bucket/../"));
  }

  @Test
  public void predicate_admitsWhatPrefixesDoNot() {
    FileUriPolicy policy =
        FileUriPolicy.builder()
            .allowPrefix("gs://inbox/in/")
            .predicate(
                c -> Objects.equals(c.mimeType(), "image/png") && c.uri().startsWith("https://"))
            .build();

    assertThat(policy.isAllowed(context("gs://inbox/in/f"))).isTrue();
    assertThat(policy.isAllowed(FileUriContext.of("https://x.test/i.png", "image/png", null)))
        .isTrue();
    assertThat(policy.isAllowed(FileUriContext.of("https://x.test/i.pdf", "application/pdf", null)))
        .isFalse();
  }

  @Test
  public void builder_setsDisallowedAction() {
    FileUriPolicy policy = FileUriPolicy.builder().onDisallowed(DisallowedAction.REJECT).build();

    assertThat(policy.onDisallowed()).isEqualTo(DisallowedAction.REJECT);
  }

  private static FileUriContext context(String uri) {
    return FileUriContext.of(uri, null, null);
  }
}
