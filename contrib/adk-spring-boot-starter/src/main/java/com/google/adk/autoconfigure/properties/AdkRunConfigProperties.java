package com.google.adk.autoconfigure.properties;

import com.google.adk.agents.RunConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "adk.run-config")
public class AdkRunConfigProperties {
    private RunConfig.StreamingMode streamingMode = RunConfig.StreamingMode.NONE;

    public RunConfig.StreamingMode getStreamingMode() {
        return streamingMode;
    }

    public void setStreamingMode(RunConfig.StreamingMode streamingMode) {
        this.streamingMode = streamingMode;
    }
}
