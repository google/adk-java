package com.google.adk.autoconfigure.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "adk.session")
public class AdkSessionProperties {
    private Type type = Type.IN_MEMORY;
    private String projectId;
    private String location;

    public enum Type {
        IN_MEMORY, VERTEX_AI
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
