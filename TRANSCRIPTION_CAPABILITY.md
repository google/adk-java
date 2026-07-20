# Audio Transcription Capability

## Overview

ADK-Java provides an optional audio transcription capability that allows agents to transcribe audio data to text. The feature is designed to be optional, lazily loaded, and configurable via environment variables.

## Features

- **Optional Feature**: Works without configuration, enables when configured
- **Lazy Loading**: Services created only when needed, cached for reuse
- **Multiple Service Support**: Extensible architecture supporting multiple transcription services
- **Async Processing**: Built on RxJava for efficient asynchronous operations
- **Streaming Support**: Supports both batch and streaming transcription
- **Environment Configuration**: All configuration via environment variables (12-Factor App compliant)

## Architecture

The transcription capability follows several design patterns:

- **Strategy Pattern**: Pluggable transcription service implementations
- **Factory Pattern**: Lazy-loaded service creation with caching
- **Builder Pattern**: Flexible configuration management
- **Optional Pattern**: Graceful degradation when not configured

### Package Structure

```
com.google.adk.transcription/
├── ServiceType.java                    # Service type enumeration
├── AudioFormat.java                    # Audio format specifications
├── TranscriptionException.java         # Custom exception
├── ServiceHealth.java                  # Health status DTO
├── TranscriptionResult.java            # Result DTO
├── TranscriptionEvent.java             # Event DTO for streaming
├── TranscriptionService.java           # Core interface
├── TranscriptionConfig.java            # Configuration class
├── config/
│   └── TranscriptionConfigLoader.java  # Environment config loader
├── client/
│   ├── WhisperRequest.java             # Request DTO
│   ├── WhisperResponse.java           # Response DTO
│   └── WhisperApiClient.java           # HTTP client
├── strategy/
│   ├── WhisperTranscriptionService.java # Service implementation
│   └── TranscriptionServiceFactory.java # Factory
└── processor/
    └── AudioChunkAggregator.java       # Chunk aggregation
```

## Configuration

### Environment Variables

**Required (for transcription to work):**
```bash
ADK_TRANSCRIPTION_ENDPOINT=https://your-transcription-service:port
```

**Optional:**
```bash
# Service type (default: inferred from endpoint)
ADK_TRANSCRIPTION_SERVICE_TYPE=whisper

# API key if required by service
ADK_TRANSCRIPTION_API_KEY=your-api-key

# Language code (default: auto-detect)
ADK_TRANSCRIPTION_LANGUAGE=en

# Timeout in seconds (default: 30)
ADK_TRANSCRIPTION_TIMEOUT_SECONDS=30

# Max retries (default: 3)
ADK_TRANSCRIPTION_MAX_RETRIES=3

# Chunk size in milliseconds for streaming (default: 500)
ADK_TRANSCRIPTION_CHUNK_SIZE_MS=500
```

## Usage

### Basic Usage: Agent Tool

The simplest way to use transcription is through the `TranscriptionTool`, which can be added to any agent:

```java
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.transcription.TranscriptionTool;
import com.google.adk.tools.FunctionTool;

// Create transcription tool (returns null if not configured)
FunctionTool transcriptionTool = TranscriptionTool.create();

if (transcriptionTool != null) {
  LlmAgent agent = LlmAgent.builder()
      .name("audio_agent")
      .model("gemini-2.5-flash")
      .instruction("Analyze audio files. Use transcribe_audio tool when needed.")
      .addTool(transcriptionTool)
      .build();
  
  // Agent can now automatically call transcribe_audio tool
}
```

### Check Availability

```java
if (TranscriptionTool.isAvailable()) {
  // Transcription is configured and available
  FunctionTool tool = TranscriptionTool.create();
  agent.addTool(tool);
} else {
  // Work without transcription
  System.out.println("Transcription not configured");
}
```

### Advanced Usage: Direct Service Access

For more control, you can use the transcription service directly:

```java
import com.google.adk.transcription.*;
import com.google.adk.transcription.config.TranscriptionConfigLoader;
import com.google.adk.transcription.strategy.TranscriptionServiceFactory;

// Load configuration from environment
Optional<TranscriptionConfig> config = TranscriptionConfigLoader.loadFromEnvironment();

if (config.isPresent()) {
  // Get service (lazy loaded, cached)
  TranscriptionService service = TranscriptionServiceFactory.getOrCreate(config.get());
  
  // Synchronous transcription
  byte[] audioData = ...; // Your audio bytes
  TranscriptionResult result = service.transcribe(audioData, config.get());
  System.out.println("Transcribed: " + result.getText());
}
```

### Async Transcription

```java
// Use RxJava Single for async transcription
Single<TranscriptionResult> resultFuture = 
    service.transcribeAsync(audioData, config.get());

resultFuture.subscribe(
    result -> System.out.println("Transcribed: " + result.getText()),
    error -> System.err.println("Error: " + error.getMessage())
);
```

### Streaming Transcription

```java
// Stream audio chunks and get transcription events
Flowable<byte[]> audioStream = ...; // Your audio stream
Flowable<TranscriptionEvent> transcriptionEvents = 
    service.transcribeStream(audioStream, config.get());

transcriptionEvents.subscribe(
    event -> {
      if (event.isFinished()) {
        System.out.println("Final: " + event.getText());
      } else {
        System.out.println("Partial: " + event.getText());
      }
    }
);
```

## Tool Function Signature

When used as an agent tool, transcription exposes the following function:

**Function Name:** `transcribe_audio`

**Parameters:**
- `audio_data` (required): Base64-encoded audio data
- `language` (optional): Language code (e.g., "en", "es", "fr")

**Returns:**
```json
{
  "text": "Transcribed text",
  "language": "en",
  "confidence": 0.95,
  "duration": 5000
}
```

## Supported Services

Currently implemented:
- **Whisper**: HTTP-based Whisper API integration

Future support planned:
- Gemini Live API
- Azure Speech Services
- AWS Transcribe

## Error Handling

Transcription operations throw `TranscriptionException` for errors. The service includes:
- Retry logic with exponential backoff
- Health check support
- Comprehensive error messages

## Thread Safety

- Service factory uses thread-safe caching
- Services are stateless and thread-safe
- Configuration objects are immutable

## Testing

### Compilation

```bash
mvn compile -DskipTests
```

### Unit Tests

```bash
mvn test -Dtest=TranscriptionConfigTest
```

## Implementation Details

### Service Factory

The `TranscriptionServiceFactory` implements lazy loading:
- Services are created only when first accessed
- Services are cached and reused
- Thread-safe implementation using `ConcurrentHashMap`

### HTTP Client

The Whisper implementation uses OkHttp for HTTP requests:
- Connection pooling
- Configurable timeouts
- Retry logic with exponential backoff
- Health check support

### Audio Processing

- Supports multiple audio formats (PCM, WAV, MP3)
- Configurable sample rates and channels
- Chunk aggregation for efficient streaming

## Limitations

- Live streaming integration (real-time audio) is not yet implemented
- PostgreSQL storage integration is not yet implemented
- Additional service implementations (Gemini, Azure, AWS) are planned

## Future Enhancements

- Real-time streaming handler integration
- Persistent storage for audio and metadata
- Additional transcription service implementations
- Enhanced error handling and retry strategies
- Performance optimizations and caching

## License

Copyright 2025 Google LLC

Licensed under the Apache License, Version 2.0.
