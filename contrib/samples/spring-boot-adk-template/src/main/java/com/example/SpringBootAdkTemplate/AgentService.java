package com.example.SpringBootAdkTemplate;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final Runner runner;
    private final LlmAgent agent;

    public AgentService(Runner runner, LlmAgent agent) {
        this.runner = runner;
        this.agent = agent;
    }

    public String getAgentInfo() {
        return "Agent created: " + agent.getClass().getSimpleName() +
                ", Runner: " + runner.getClass().getSimpleName();
    }
}
