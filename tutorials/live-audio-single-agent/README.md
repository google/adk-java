# Live Audio Single-Agent

A tutorial demonstrating how the ADK (Agent Development Kit) automatically configures **inputAudioTranscription** and **outputAudioTranscription** for single-agent live scenarios. This tutorial showcases that the feature now works for all live scenarios, not just multi-agent scenarios.

## What This Demonstrates

This tutorial verifies the feature change in `Runner.java` that enables automatic transcription configuration for all live scenarios:

**Before:** Only multi-agent scenarios got automatic transcription
```java
if (liveRequestQueue.isPresent() && !this.agent.subAgents().isEmpty())
```

**After:** All live scenarios (including single-agent) get automatic transcription
```java
if (liveRequestQueue.isPresent())
```

When you use this single-agent with live audio, the ADK automatically configures:
- **inputAudioTranscription** - Transcribes user speech to text
- **outputAudioTranscription** - Transcribes agent speech to text

## Setup API Key

```shell
export GOOGLE_GENAI_API_KEY={YOUR-KEY}
```

## Go to Tutorial Directory

```shell
cd tutorials/live-audio-single-agent
```

## Running the Agent

Start the server:

```shell
mvn exec:java
```

This starts the ADK web server with a single weather agent (`weather_agent`) that supports live audio using the `gemini-2.0-flash-live-001` model.

## Usage

Once running, you can interact with the agent through:
- **Web interface:** `http://localhost:8080`
- **Agent name:** `weather_agent`
- **Try asking:** "What's the weather in Tokyo?" or "How's the weather in New York?"

### Testing with Live Audio

1. Open the web interface at `http://localhost:8080`
2. Enable your microphone
3. Speak to the agent: "What's the weather in Tokyo?"
4. The agent will:
   - Automatically transcribe your speech to text (inputAudioTranscription)
   - Process the request and call the `getWeather` tool
   - Respond with audio (automatically transcribed via outputAudioTranscription)

## Learn More

See https://google.github.io/adk-docs/get-started/quickstart/#java for more information.
