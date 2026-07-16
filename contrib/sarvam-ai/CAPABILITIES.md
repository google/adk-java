# Sarvam AI - ADK Integration Capabilities

## Overview

The Sarvam AI module provides a comprehensive, production-grade integration of Sarvam AI services into the Google Agent Development Kit (ADK) for Java. It spans five service domains -- Chat, Speech-to-Text, Text-to-Speech, Vision, and Live Connections -- covering both REST and WebSocket protocols with full observability, resilience, and multi-turn agentic support.

**Module path:** `contrib/sarvam-ai`
**Package:** `com.google.adk.models.sarvamai`
**Branch:** `sarvam-ai`

---

## 1. Chat Completions (LLM)

**Class:** `SarvamAi` extends `BaseLlm`
**Endpoint:** `POST /v1/chat/completions` (OpenAI-compatible)

| Capability | Details |
|---|---|
| Blocking (non-streaming) | Full request/response cycle via `generateContent(request, false)` |
| SSE Streaming | Real-time token-by-token delivery via `generateContent(request, true)` with backpressure (RxJava `Flowable`) |
| Function / Tool Calling | ADK `FunctionDeclaration` serialized to OpenAI `tools` JSON with `tool_choice: auto` |
| Multi-turn Tool History | Prior `tool_calls` correctly formatted as assistant messages with `tool_call_id`, `function.name`, `function.arguments`; tool responses sent as `role: tool` |
| Streaming Function Calls | Chunked `name` and `arguments` accumulated across SSE deltas, emitted as final `FunctionCall` Part |
| Token Usage Tracking | `prompt_tokens`, `completion_tokens`, `total_tokens` extracted for both blocking and streaming modes. Streaming uses `stream_options: {"include_usage": true}` |
| System Instructions | ADK `GenerateContentConfig.systemInstruction` mapped to OpenAI `system` role message |
| Temperature Control | Forwarded from `GenerateContentConfig.temperature` (default 0.7) |
| Max Output Tokens | `GenerateContentConfig.maxOutputTokens` forwarded as `max_tokens` |
| Top-P Sampling | Configurable via `SarvamAiConfig.topP()` |
| Frequency / Presence Penalty | Configurable via `SarvamAiConfig` builder |
| Reasoning Effort | Sarvam-specific `reasoning_effort` parameter (low / medium / high) |
| Wiki Grounding | Sarvam-specific `wiki_grounding` toggle for factual grounding |
| Role Translation | ADK `model` -> OpenAI `assistant`, `user` -> `user`, `functionResponse` -> `tool` |
| Schema Normalization | Type strings lowercased, nested `items.properties` recursively normalized for OpenAI schema compatibility |
| Graceful Degradation | Empty choices return empty text response instead of crashing |

### Dual Implementation

| Implementation | Location | Use Case |
|---|---|---|
| `SarvamBaseLM` | `core/src/main/java/.../models/SarvamBaseLM.java` | Lightweight, env-var driven. Used by `AgentModelConfig` and `LlmRegistry` for `Sarvam\|model` config strings |
| `SarvamAi` | `contrib/sarvam-ai/src/.../SarvamAi.java` | Full-featured, Builder-pattern, OkHttp-based. Supports all chat parameters plus subservice access |

---

## 2. Speech-to-Text (STT)

**Class:** `SarvamSttService` implements `TranscriptionService`
**Model:** `saaras:v3`

| Capability | Details |
|---|---|
| REST Synchronous | `transcribe(byte[] audioData, TranscriptionConfig)` via `POST /speech-to-text` with multipart/form-data |
| REST Async | `transcribeAsync()` executes on RxJava IO scheduler |
| WebSocket Streaming | Real-time streaming via `wss://api.sarvam.ai/speech-to-text/streaming` with VAD (Voice Activity Detection) signals |
| Transcription Modes | `transcribe`, `translate`, `verbatim`, `translit`, `codemix` |
| Language Detection | Auto-detection supported; explicit BCP-47 codes (e.g., `hi-IN`, `en-IN`) also accepted |
| VAD Signals | `speech_start` and `speech_end` events for voice activity boundaries |
| ADK TranscriptionService | Full implementation of ADK's `TranscriptionService` interface including `isAvailable()`, `getServiceType()`, `getHealth()` |

---

## 3. Text-to-Speech (TTS)

**Class:** `SarvamTtsService`
**Model:** `bulbul:v3`

| Capability | Details |
|---|---|
| REST Synchronous | `synthesize(text, languageCode)` returns decoded WAV audio bytes |
| REST Async | `synthesizeAsync()` on IO scheduler |
| WebSocket Streaming | `synthesizeStream()` via `wss://api.sarvam.ai/text-to-speech/streaming` for low-latency progressive audio chunk delivery |
| 30+ Speaker Voices | Configurable via `SarvamAiConfig.ttsSpeaker()` (default: `shubh`) |
| Pace Control | Adjustable speech pace (0.5x to 2.0x) |
| Sample Rate | Configurable output sample rate |
| Base64 Decoding | Audio chunks automatically decoded from base64 to raw bytes |
| WebSocket Lifecycle | Config frame -> text frame -> flush frame -> audio chunks -> final event -> close |

---

## 4. Vision / Document Intelligence

**Class:** `SarvamVisionService`
**Model:** Sarvam Vision 3B VLM

| Capability | Details |
|---|---|
| Multi-Language OCR | 23 languages (22 Indian + English) |
| Input Formats | PDF, PNG, JPG, ZIP |
| Output Formats | HTML or Markdown |
| Async Job Pipeline | `createJob` -> `uploadDocument` (presigned URL) -> `startJob` -> `getJobStatus` (poll) -> `downloadResults` |
| Convenience Method | `processDocument(filePath, languageCode, outputFormat)` runs the full pipeline with adaptive exponential backoff polling |
| Polling Backoff | Starts at 2s, doubles up to 10s cap, max 60 polls (~2 min timeout) |

---

## 5. Live Bidirectional Connection

**Class:** `SarvamAiLlmConnection` implements `BaseLlmConnection`

| Capability | Details |
|---|---|
| Multi-Turn Context | Maintains conversation history across turns, accumulates full model responses |
| sendHistory | Replace full conversation context |
| sendContent | Append a single turn and trigger streaming response |
| receive | Returns `Flowable<LlmResponse>` via `PublishSubject` for reactive consumers |
| Thread Safety | History list synchronized for concurrent access |
| Realtime Guard | `sendRealtime(Blob)` throws `UnsupportedOperationException` with guidance to use STT/TTS services |

---

## 6. Resilience & Configuration

### Retry with Exponential Backoff

**Class:** `SarvamRetryInterceptor` (OkHttp `Interceptor`)

| Parameter | Value |
|---|---|
| Retryable codes | 429 (rate limit), 503, 5xx (server errors) |
| Base delay | 500ms |
| Max delay | 30s |
| Strategy | Exponential backoff with 20% jitter |
| Default max retries | 3 |

### Immutable Configuration

**Class:** `SarvamAiConfig` (Builder pattern)

| Parameter | Default |
|---|---|
| Chat endpoint | `https://api.sarvam.ai/v1/chat/completions` |
| STT endpoint | `https://api.sarvam.ai/speech-to-text` |
| STT WebSocket | `wss://api.sarvam.ai/speech-to-text/streaming` |
| TTS endpoint | `https://api.sarvam.ai/text-to-speech` |
| TTS WebSocket | `wss://api.sarvam.ai/text-to-speech/streaming` |
| Vision endpoint | `https://api.sarvam.ai/document-intelligence` |
| Connect timeout | 30s |
| Read timeout | 120s |
| Max retries | 3 |
| API key resolution | Explicit value > `SARVAM_API_KEY` env var |

### Structured Error Handling

**Class:** `SarvamAiException` extends `RuntimeException`

| Field | Purpose |
|---|---|
| `statusCode` | HTTP status code from API |
| `errorCode` | Sarvam-specific error code |
| `requestId` | Sarvam request ID for support tracing |
| `isRetryable()` | Programmatic check (429, 503, 5xx) |

---

## 7. Authentication

| Method | Header | Used By |
|---|---|---|
| API Subscription Key | `api-subscription-key: <key>` | `SarvamAi`, STT, TTS, Vision (contrib module) |
| Bearer Token | `Authorization: Bearer <key>` | `SarvamBaseLM` (core module, OpenAI-compatible) |
| Key Resolution | `SARVAM_API_KEY` env var or explicit via Builder | Both |
| Fail-Fast Validation | Warning logged at construction if key is missing | `SarvamBaseLM` |

---

## 8. Test Coverage

| Test Class | Tests | Scope |
|---|---|---|
| `SarvamBaseLMTest` | 10 | Response parsing (text, null, tool calls), construction, connection type |
| `SarvamAiTest` | - | Chat completion blocking and streaming |
| `SarvamAiConfigTest` | - | Config builder validation, defaults, env var resolution |
| `ChatRequestTest` | - | Request serialization from LlmRequest |
| `SarvamSttServiceTest` | - | STT REST and WebSocket transcription |
| `SarvamTtsServiceTest` | - | TTS REST and WebSocket synthesis |
| `SarvamRetryInterceptorTest` | - | Retry logic, delay calculation, jitter |
| `SarvamIntegrationTest` (rae) | 20 | End-to-end config wiring across properties, YAML, LlmRegistry |

---

## 9. RAE Integration (Consumer Project)

| Integration Point | Mechanism | File |
|---|---|---|
| Code-based agents | `AgentModelConfig` recognizes `Sarvam\|` prefix, instantiates `SarvamBaseLM` | `AgentModelConfig.java` |
| YAML-based agents | `LlmRegistry.registerLlm("Sarvam\\|.*", ...)` factory | `ApplicationRegistry.java` |
| Model metadata | `sarvam:` provider in `models.yaml` with feature declarations | `models.yaml` |
| Config format | `Sarvam\|sarvam-m` -- single string works across both paths | `agent-models.properties` + `*.yaml` |
| Global coverage | 43 code-based + 28 YAML agent configs switched to Sarvam | All agent config files |

---

## Architecture Summary

```
contrib/sarvam-ai/
  src/main/java/com/google/adk/models/sarvamai/
    SarvamAi.java                  # BaseLlm (chat, Builder pattern, OkHttp)
    SarvamAiConfig.java            # Immutable config for all services
    SarvamAiException.java         # Structured error with status/code/requestId
    SarvamAiLlmConnection.java     # Live bidirectional multi-turn connection
    SarvamRetryInterceptor.java    # Exponential backoff with jitter
    chat/
      ChatRequest.java             # OpenAI-compatible request model
      ChatResponse.java            # Response deserialization
      ChatChoice.java              # Choice wrapper
      ChatMessage.java             # Message model
      ChatUsage.java               # Token usage tracking
    stt/
      SarvamSttService.java        # REST + WebSocket STT (TranscriptionService)
    tts/
      SarvamTtsService.java        # REST + WebSocket TTS
      TtsRequest.java              # TTS request model
      TtsResponse.java             # TTS response model
    vision/
      SarvamVisionService.java     # Async job pipeline for document OCR

core/src/main/java/com/google/adk/models/
    SarvamBaseLM.java              # Lightweight BaseLlm for agent config integration
```
