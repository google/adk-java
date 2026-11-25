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

import com.google.adk.a2a.converters.FileUriPolicy.FileUriContext;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** URIs that no policy admits, not even {@link FileUriPolicy#allowAll()} or a predicate. */
@RunWith(Parameterized.class)
public class FileUriPolicyNeverSafeTest {

  @Parameters(name = "{index}: {0}")
  public static ImmutableList<String> parameters() {
    return ImmutableList.of(
        "file:///etc/passwd",
        "file://fileserver/etc/passwd",
        "FILE://host/x",
        "data:text/plain;base64,aGk=",
        "javascript:alert(1)",
        "ftp://example.com/f",
        "jar:file:///a.jar!/x",
        "mailto:a@example.com",
        "http://localhost/admin",
        "http://localhost.localdomain/x",
        "http://ip6-localhost/x",
        "http://ip6-loopback/x",
        "http://instance-data/latest/meta-data/",
        "http://instance-data.ec2.internal/latest/meta-data/",
        "http://foo.localhost/x",
        "http://127.0.0.1/x",
        "http://2130706433/x",
        "http://0x7f.1/x",
        "http://169.254.169.254/computeMetadata/v1/",
        "http://metadata.google.internal/computeMetadata/v1/",
        "http://metadata.google.internal./computeMetadata/v1/",
        "http://metadata/computeMetadata/v1/",
        "http://metadata.goog/computeMetadata/v1/",
        "http://metadata.goog./computeMetadata/v1/",
        "http://METADATA.GOOGLE/computeMetadata/v1/",
        "http://[::1]/x",
        "https://user:pass@example.com/f",
        "gs://bucket/a/../secret",
        "gs://bucket/a/./f",
        "https://cdn.example.com/inbox/..;/secret",
        "https://cdn.example.com/inbox/..;v=1/secret",
        "https://cdn.example.com/inbox/..%3B/secret",
        "https://cdn.example.com/inbox/.;x/secret",
        "gs://bucket/a/%2e%2e/secret",
        "gs://bucket/a/%2E%2E/secret",
        "gs://bucket/a%2fb",
        "gs://bucket/inbox/?/../../secret",
        "gs://bucket/inbox/a#/../../secret",
        "gs://bucket/inbox/..?x",
        "gs://bucket/inbox/..#x",
        "gs://bucket?/f",
        "https://cdn.example.com/inbox/a%2F..%2Fsecret",
        "https://cdn.example.com/inbox/%252F/secret",
        "gs://bucket/a%5cb",
        "gs://bucket//f",
        "gs://bucket/f%00",
        "gs://bucket/f%0a",
        "gs://bucket/a/%252e%252e/secret",
        "https://cdn.example.com/inbox/%2%65%2%65/secret",
        "https://cdn.example.com/inbox/%%32%65%%32%65/secret",
        "gs://bucket/a/ /../x",
        "http://local host/x",
        "http://%6c%6f%63%61%6c%68%6f%73%74/admin",
        "http://127.0.0.%31/admin",
        "http://169.254.169.%32%35%34/computeMetadata/v1/",
        "http://metadata%2egoogle%2einternal/computeMetadata/v1/",
        "http://127\u30020\u30020\u30021/admin",
        "http://localhost:8080/admin",
        "http://127.0.0.1:80/admin",
        "http://localhost:80:80/admin",
        "http://127.0.0.1:80:80/admin",
        "http://0:0:0:0:0:0:0:1/admin",
        "http://example.com:/f",
        "http://example.com:999999/f",
        "https://\u212A.example.com/f",
        "gs://-bucket/f",
        "gs://ab/f",
        "files/../abc",
        "files/abc/def",
        "relative/path",
        "gs:///no-bucket",
        "not a uri",
        "",
        "gs://bucket/f\n");
  }

  private final FileUriContext context;

  public FileUriPolicyNeverSafeTest(String uri) {
    this.context = FileUriContext.of(uri, null, null);
  }

  @Test
  public void allowAll_deniesUri() {
    assertThat(FileUriPolicy.allowAll().isAllowed(context)).isFalse();
  }

  @Test
  public void predicateAdmittingEverything_deniesUri() {
    FileUriPolicy policy = FileUriPolicy.builder().predicate(c -> true).build();

    assertThat(policy.isAllowed(context)).isFalse();
  }
}
