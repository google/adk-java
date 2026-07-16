/*
 * Author: Sandeep Belgavi
 * Date: June 19, 2026
 */
package com.google.adk.tutorials;

import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.Runner;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Modality;
import com.google.genai.types.ModalityTokenCount;
import com.google.genai.types.Part;

public class LiveAudioTokensTest {
  public static void main(String[] args) {
    LlmAgent agent =
        LlmAgent.builder()
            .name("audio_agent")
            .model("gemini-3.1-flash-live-preview")
            .instruction(
                "You are a helpful assistant. Please say 'Hello, how can I help you today?'")
            .build();

    Runner runner = Runner.builder().agent(agent).appName("audio_test").build();

    RunConfig runConfig =
        RunConfig.builder()
            .autoCreateSession(true)
            .streamingMode(RunConfig.StreamingMode.BIDI)
            .responseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .build();

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.fromText("Please introduce yourself.")))
            .build();

    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.content(userMessage);

    System.out.println("Sending request to model...");

    runner
        .runLive("user1", "session1", liveRequestQueue, runConfig)
        .doOnNext(
            event -> {
              System.out.println(
                  "Got event from author: "
                      + event.author()
                      + " content: "
                      + event.content().map(c -> c.parts().map(ps -> ps.size()).orElse(0)).orElse(0)
                      + " parts");
              if (event.author() != null && event.author().equals("audio_agent")) {
                if (event.turnComplete().orElse(false)) {
                  liveRequestQueue.close();
                }
                if (event.content().isPresent()) {
                  Content c = event.content().get();
                  for (Part p : c.parts().get()) {
                    if (p.text().isPresent()) {
                      System.out.println("Text: " + p.text().get());
                    }
                  }
                }
                if (event.usageMetadata().isPresent()) {
                  GenerateContentResponseUsageMetadata usage = event.usageMetadata().get();
                  System.out.println("Total Tokens: " + usage.totalTokenCount().orElse(0));
                  System.out.println("Usage details: " + usage);

                  if (usage.promptTokensDetails().isPresent()) {
                    for (ModalityTokenCount mtc : usage.promptTokensDetails().get()) {
                      System.out.println(
                          "Prompt Modality: "
                              + mtc.modality().get()
                              + " Tokens: "
                              + mtc.tokenCount().get());
                    }
                  }
                  if (usage.candidatesTokensDetails().isPresent()) {
                    for (ModalityTokenCount mtc : usage.candidatesTokensDetails().get()) {
                      System.out.println(
                          "Completion Modality: "
                              + mtc.modality().get()
                              + " Tokens: "
                              + mtc.tokenCount().get());
                    }
                  }
                }
              }
            })
        .doOnError(Throwable::printStackTrace)
        .blockingSubscribe();

    System.out.println("Done.");
    System.exit(0);
  }
}
