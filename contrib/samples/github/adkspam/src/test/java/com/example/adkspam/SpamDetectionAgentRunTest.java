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
package com.example.adkspam;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure pre-filtering and prompt-building logic in {@link SpamDetectionAgentRun}:
 * code-block stripping/truncation, maintainer/bot detection, review-item assembly, and the review
 * prompt builder.
 */
final class SpamDetectionAgentRunTest {

  // ---- cleanText ----

  @Test
  void cleanText_stripsCodeBlocks() {
    String input = "before ```code here``` after";
    String cleaned = SpamDetectionAgentRun.cleanText(input, 1500);
    assertThat(cleaned).doesNotContain("code here");
    assertThat(cleaned).contains("[CODE BLOCK REMOVED]");
  }

  @Test
  void cleanText_stripsMultilineCodeBlocks() {
    String input = "x\n```\nline1\nline2\n```\ny";
    String cleaned = SpamDetectionAgentRun.cleanText(input, 1500);
    assertThat(cleaned).doesNotContain("line1");
    assertThat(cleaned).contains("[CODE BLOCK REMOVED]");
  }

  @Test
  void cleanText_truncatesLongText() {
    String input = "a".repeat(2000);
    String cleaned = SpamDetectionAgentRun.cleanText(input, 1500);
    assertThat(cleaned).contains("...[TRUNCATED]");
    assertThat(cleaned).startsWith("a".repeat(1500));
  }

  @Test
  void cleanText_nullBecomesEmpty() {
    assertThat(SpamDetectionAgentRun.cleanText(null, 1500)).isEmpty();
  }

  // ---- isMaintainerOrBot ----

  @Test
  void isMaintainerOrBot_recognizesMaintainersBotsAndAppAccounts() {
    Set<String> maintainers = ImmutableSet.of("alice", "bob");
    assertThat(SpamDetectionAgentRun.isMaintainerOrBot("alice", maintainers, "adk-bot")).isTrue();
    assertThat(SpamDetectionAgentRun.isMaintainerOrBot("adk-bot", maintainers, "adk-bot")).isTrue();
    assertThat(SpamDetectionAgentRun.isMaintainerOrBot("dependabot[bot]", maintainers, "adk-bot"))
        .isTrue();
  }

  @Test
  void isMaintainerOrBot_regularUserIsScanned() {
    Set<String> maintainers = ImmutableSet.of("alice");
    assertThat(SpamDetectionAgentRun.isMaintainerOrBot("randomuser", maintainers, "adk-bot"))
        .isFalse();
    assertThat(SpamDetectionAgentRun.isMaintainerOrBot(null, maintainers, "adk-bot")).isFalse();
  }

  // ---- buildReviewItems ----

  @Test
  void buildReviewItems_includesIssueBodyWhenAuthorIsNotMaintainer() {
    List<String> items =
        SpamDetectionAgentRun.buildReviewItems(
            "randomuser",
            "Buy cheap shoes at example.com",
            ImmutableList.of(),
            ImmutableSet.of("alice"),
            "adk-bot",
            1500);
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).contains("Original Issue");
    assertThat(items.get(0)).contains("@randomuser");
    assertThat(items.get(0)).contains("Buy cheap shoes");
  }

  @Test
  void buildReviewItems_skipsIssueBodyWhenAuthorIsMaintainer() {
    List<String> items =
        SpamDetectionAgentRun.buildReviewItems(
            "alice",
            "Legit maintainer description",
            ImmutableList.of(),
            ImmutableSet.of("alice"),
            "adk-bot",
            1500);
    assertThat(items).isEmpty();
  }

  @Test
  void buildReviewItems_skipsMaintainerAndBotCommentsButKeepsUsers() {
    List<Map<String, Object>> comments =
        ImmutableList.of(
            ImmutableMap.of("author", "alice", "body", "maintainer reply"),
            ImmutableMap.of("author", "adk-bot", "body", "bot reply"),
            ImmutableMap.of("author", "ci[bot]", "body", "ci reply"),
            ImmutableMap.of("author", "spammer", "body", "check my site"));

    List<String> items =
        SpamDetectionAgentRun.buildReviewItems(
            "alice", "x", comments, ImmutableSet.of("alice"), "adk-bot", 1500);

    // Maintainer issue author -> body skipped; only the non-maintainer comment remains.
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).contains("@spammer");
    assertThat(items.get(0)).contains("check my site");
  }

  @Test
  void buildReviewItems_cleansAndTruncatesCommentBodies() {
    List<Map<String, Object>> comments =
        ImmutableList.of(
            ImmutableMap.of("author", "spammer", "body", "```secret```" + "z".repeat(2000)));
    List<String> items =
        SpamDetectionAgentRun.buildReviewItems(
            "alice", "x", comments, ImmutableSet.of("alice"), "adk-bot", 1500);
    assertThat(items).hasSize(1);
    assertThat(items.get(0)).doesNotContain("secret");
    assertThat(items.get(0)).contains("[CODE BLOCK REMOVED]");
    assertThat(items.get(0)).contains("...[TRUNCATED]");
  }

  // ---- buildReviewPrompt ----

  @Test
  void buildReviewPrompt_includesIssueNumberAndFencedText() {
    String prompt = SpamDetectionAgentRun.buildReviewPrompt(123, "some text");
    assertThat(prompt).contains("#123");
    assertThat(prompt).contains("some text");
    assertThat(prompt).contains("UNTRUSTED");
    assertThat(prompt).contains("BEGIN TEXT TO REVIEW");
  }
}
