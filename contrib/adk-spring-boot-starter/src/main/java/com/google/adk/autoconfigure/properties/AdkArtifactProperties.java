package com.google.adk.autoconfigure.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "adk.artifacts")
public class AdkArtifactProperties {
    private boolean gcsEnabled = false;
    private String bucketName;

    public boolean isGcsEnabled() {
        return gcsEnabled;
    }

    public void setGcsEnabled(boolean gcsEnabled) {
        this.gcsEnabled = gcsEnabled;
    }

    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }
}
