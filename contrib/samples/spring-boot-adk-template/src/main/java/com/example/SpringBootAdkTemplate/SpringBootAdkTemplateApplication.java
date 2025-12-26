package com.example.SpringBootAdkTemplate;

import com.google.adk.agents.RunConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
public class SpringBootAdkTemplateApplication {

	public static void main(String[] args) {
		ApplicationContext context = SpringApplication.run(SpringBootAdkTemplateApplication.class, args);

		// --- TEST SCENARIO FOR ADK STARTER (REMOVE IN PRODUCTION) ---
		System.out.println("\n=== ADK Starter Verification ===");

		// Verify AgentService and injected Agent
		AgentService agentService = context.getBean(AgentService.class);
		System.out.println("Service Info: " + agentService.getAgentInfo());

        RunConfig runConfig = context.getBean(RunConfig.class);
        System.out.println("Run Config streaming mode: " + runConfig.streamingMode());

		// Verify Auto-Configured Beans
		printBeanInfo(context, "com.google.adk.agents.RunConfig");
		printBeanInfo(context, "com.google.adk.runner.Runner");
		printBeanInfo(context, "com.google.adk.artifacts.BaseArtifactService");
		printBeanInfo(context, "com.google.adk.sessions.BaseSessionService");
		printBeanInfo(context, "com.google.adk.memory.BaseMemoryService");

		System.out.println("================================\n");
		// ------------------------------------------------------------
	}

	private static void printBeanInfo(ApplicationContext context, String className) {
		try {
			Class<?> clazz = Class.forName(className);
			Object bean = context.getBean(clazz);
			System.out.println(clazz.getSimpleName() + ": " + bean.getClass().getName());
		} catch (ClassNotFoundException e) {
			System.out.println(className + ": Class not found");
		} catch (Exception e) {
			System.out.println(className + ": Bean not found (" + e.getMessage() + ")");
		}
	}

}
