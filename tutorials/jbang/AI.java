//JAVA 17+
//PREVIEW
//REPOS enola=https://docs.enola.dev/maven-repo/,mavencentral,jitpack
//DEPS com.google.adk:google-adk-dev:0.3.0
//
// All this ^^^ should not be removed because https://JBang.dev needs this...

import com.google.adk.agents.LlmAgent;
import com.google.adk.web.AdkWebServer;

void main() {
    AdkWebServer.start(LlmAgent.builder().name("AI").model("gemini-2.0-flash").instruction("Be very grumpy!").build());
}
