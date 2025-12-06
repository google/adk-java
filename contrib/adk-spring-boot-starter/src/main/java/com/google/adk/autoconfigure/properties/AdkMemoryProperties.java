package com.google.adk.autoconfigure.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "adk.memory")
public class AdkMemoryProperties {
    private Type type = Type.IN_MEMORY;

    public enum Type {
        IN_MEMORY, VERTEX_AI
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }
}
