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

package com.google.adk.web.config;

import static com.google.common.truth.Truth.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * One reading of {@code adk.web.backend-url}. The value the dev UI is served and the prefix its
 * entry redirect carries come from the same parse, so they cannot disagree.
 */
public class BackendUrlTest {

  private static final String CONFIGURED = "https://gw.example.com/my-app";

  @Test
  public void unset_isEmptyEverywhere() {
    for (String in : new String[] {null, "", "   "}) {
      assertThat(BackendUrl.from(in).value()).isEmpty();
      assertThat(BackendUrl.from(in).pathPrefix()).isEmpty();
    }
  }

  @Test
  public void absoluteUrl_isServedAndSuppliesThePrefix() {
    BackendUrl url = BackendUrl.from(CONFIGURED);

    assertThat(url.value()).isEqualTo(CONFIGURED);
    assertThat(url.pathPrefix()).isEqualTo("/my-app");
  }

  @Test
  public void trailingSlashes_areStripped() {
    // The UI appends paths starting with a slash, so a trailing one would yield //run_live.
    assertThat(BackendUrl.from(CONFIGURED + "/").value()).isEqualTo(CONFIGURED);
    assertThat(BackendUrl.from(CONFIGURED + "///").pathPrefix()).isEqualTo("/my-app");
    assertThat(BackendUrl.from("  " + CONFIGURED + "  ").value()).isEqualTo(CONFIGURED);
  }

  @Test
  public void hostWithoutPath_hasNoPrefix() {
    assertThat(BackendUrl.from("https://gw.example.com").pathPrefix()).isEmpty();
    assertThat(BackendUrl.from("https://gw.example.com/").pathPrefix()).isEmpty();
  }

  @Test
  public void percentEncoding_isKept() {
    // This goes into a Location header, so decoding would re-encode wrongly and %2F would
    // turn into a path separator.
    assertThat(BackendUrl.from("https://gw.example.com/my%20app").pathPrefix())
        .isEqualTo("/my%20app");
    assertThat(BackendUrl.from("https://gw.example.com/a%2Fb").pathPrefix()).isEqualTo("/a%2Fb");
  }

  @Test
  public void doubledSlash_doesNotBecomeAHost() {
    // "//my-app/dev-ui/" is protocol-relative: a browser resolves it to the host "my-app".
    assertThat(BackendUrl.from("https://gw.example.com//my-app").pathPrefix()).isEqualTo("/my-app");
  }

  @Test
  public void unusableValue_isStillServedButWarns() {
    // Never silently discarded, because it is an explicit setting.
    assertThat(BackendUrl.from("/my-app").value()).isEqualTo("/my-app");
    assertThat(BackendUrl.from("HTTPS://gw.example.com/x").value())
        .isEqualTo("HTTPS://gw.example.com/x");
    assertThat(BackendUrl.from("http://").value()).isEqualTo("http://");

    assertThat(warningsFor("/my-app")).hasSize(1);
    assertThat(warningsFor("/my-app").get(0)).contains("/my-app");
    assertThat(warningsFor("HTTPS://gw.example.com/x")).hasSize(1);
    assertThat(warningsFor("http://")).hasSize(1);
  }

  @Test
  public void slashOnly_isNotEmptiedByTheSlashTrim() {
    // Emptying this would make the served value fall back to whatever the bundled config says,
    // silently losing an explicit setting.
    assertThat(BackendUrl.from("/").value()).isEqualTo("/");
    assertThat(BackendUrl.from("/").pathPrefix()).isEmpty();
  }

  @Test
  public void queryFragmentOrUserinfo_yieldsNoPrefixAndWarns() {
    // The UI appends onto the value, so these would land in the middle of every request.
    for (String in :
        new String[] {
          "https://gw.example.com/a/b?q=1",
          "https://gw.example.com/a/b#f",
          "https://user:pass@gw.example.com/my-app"
        }) {
      assertThat(BackendUrl.from(in).pathPrefix()).isEmpty();
      assertThat(warningsFor(in)).hasSize(1);
    }
  }

  @Test
  public void valueWithoutALeadingSlashPath_yieldsNoPrefix() {
    // "gw.example.com/dev-ui/" in a Location is a relative redirect, i.e. a guess.
    assertThat(BackendUrl.from("gw.example.com").pathPrefix()).isEmpty();
    assertThat(warningsFor("gw.example.com")).hasSize(1);
  }

  @Test
  public void unparseableValue_yieldsNoPrefixAndWarns() {
    // The old split lost the prefix here silently; one parse now warns instead.
    String malformed = "https://gw.example.com/my app";

    assertThat(BackendUrl.from(malformed).value()).isEqualTo(malformed);
    assertThat(BackendUrl.from(malformed).pathPrefix()).isEmpty();
    assertThat(warningsFor(malformed)).hasSize(1);
  }

  @Test
  public void usableValue_doesNotWarn() {
    assertThat(warningsFor(CONFIGURED)).isEmpty();
    assertThat(warningsFor(CONFIGURED + "/")).isEmpty();
    assertThat(warningsFor("")).isEmpty();
  }

  /** The WARN messages logged while interpreting {@code configured}. */
  private static List<String> warningsFor(String configured) {
    Logger logger = (Logger) LoggerFactory.getLogger(BackendUrl.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      BackendUrl unused = BackendUrl.from(configured);
    } finally {
      logger.detachAppender(appender);
    }
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }
}
