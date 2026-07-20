# Server-Sent Events (SSE) Streaming Service

## Overview

This module provides a clean, reusable, industry-standard implementation of Server-Sent Events (SSE) streaming for agent execution in ADK Java. The implementation follows best practices and provides both generic infrastructure and domain-specific extension points.

**Author:** Sandeep Belgavi  
**Date:** June 24, 2026

## Architecture

### Components

1. **SseEventStreamService** - Generic SSE streaming service
2. **EventProcessor** - Interface for custom event processing
3. **PassThroughEventProcessor** - Default pass-through processor
4. **Generic SSE Infrastructure** - Reusable for any domain

### Design Principles

- **Separation of Concerns**: Generic infrastructure vs domain-specific logic
- **Extensibility**: Easy to add custom event processors
- **Reusability**: Generic service usable by all applications
- **Clean Code**: Well-documented, testable, maintainable
- **Industry Best Practices**: Follows Spring Boot and SSE standards

## Quick Start

### Basic Usage (Generic Endpoint)

```java
// Already available at POST /run_sse
// Uses PassThroughEventProcessor by default
```

### Domain-Specific Usage

```java
@RestController
public class MyDomainController {
  
  @Autowired
  private SseEventStreamService sseEventStreamService;
  
  @Autowired
  private RunnerService runnerService;
  
  @PostMapping(value = "/mydomain/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter myDomainSse(@RequestBody MyDomainRequest request) {
    Runner runner = runnerService.getRunner(request.getAppName());
    RunConfig runConfig = RunConfig.builder()
        .setStreamingMode(StreamingMode.SSE)
        .build();
    
    MyEventProcessor processor = new MyEventProcessor(request);
    
    return sseEventStreamService.streamEvents(
        runner,
        request.getAppName(),
        request.getUserId(),
        request.getSessionId(),
        Content.fromParts(Part.fromText(request.getQuery())),
        runConfig,
        buildStateDelta(request),
        processor
    );
  }
}
```

## Creating Custom Event Processors

### Simple Processor

```java
@Component
public class MyEventProcessor implements EventProcessor {
  
  @Override
  public Optional<String> processEvent(Event event, Map<String, Object> context) {
    // Transform or filter events
    if (shouldSend(event)) {
      return Optional.of(transformEvent(event));
    }
    return Optional.empty(); // Filter out
  }
  
  @Override
  public void onStreamStart(SseEmitter emitter, Map<String, Object> context) {
    // Send initial event
    emitter.send(SseEmitter.event()
        .name("connected")
        .data("{\"status\":\"connected\"}"));
  }
  
  @Override
  public void onStreamComplete(SseEmitter emitter, Map<String, Object> context) {
    // Send final event
    emitter.send(SseEmitter.event()
        .name("done")
        .data("{\"status\":\"complete\"}"));
  }
}
```

### Accumulating Processor

```java
public class AccumulatingEventProcessor implements EventProcessor {
  private final AtomicReference<String> accumulated = new AtomicReference<>("");
  
  @Override
  public Optional<String> processEvent(Event event, Map<String, Object> context) {
    // Accumulate events, don't send until complete
    accumulate(event);
    return Optional.empty(); // Filter out intermediate events
  }
  
  @Override
  public void onStreamComplete(SseEmitter emitter, Map<String, Object> context) {
    // Send accumulated result
    emitter.send(SseEmitter.event()
        .name("message")
        .data(accumulated.get()));
  }
}
```

## API Reference

### SseEventStreamService

#### Methods

- `streamEvents(Runner, String, String, String, Content, RunConfig, Map, EventProcessor)`  
  Streams events with default timeout (1 hour)

- `streamEvents(Runner, String, String, String, Content, RunConfig, Map, EventProcessor, long)`  
  Streams events with custom timeout

- `shutdown()`  
  Gracefully shuts down the executor service

### EventProcessor Interface

#### Methods

- `processEvent(Event, Map<String, Object>)`  
  Process and optionally transform/filter events

- `onStreamStart(SseEmitter, Map<String, Object>)`  
  Called when stream starts

- `onStreamComplete(SseEmitter, Map<String, Object>)`  
  Called when stream completes normally

- `onStreamError(SseEmitter, Throwable, Map<String, Object>)`  
  Called when stream encounters an error

## Examples

See the `examples` package for complete implementations:
- Applications can create their own domain-specific controllers and processors
- Use `EventProcessor` interface to implement custom event handling

## Testing

### Unit Tests

- `SseEventStreamServiceTest` - Service unit tests
- `EventProcessorTest` - Processor interface tests

### Integration Tests

- `SseEventStreamServiceIntegrationTest` - End-to-end integration tests

## Best Practices

1. **Use Generic Service**: Always use `SseEventStreamService` instead of manual SSE
2. **Create Domain Processors**: Implement `EventProcessor` for domain-specific logic
3. **Keep Controllers Thin**: Controllers should only handle HTTP concerns
4. **Validate Early**: Validate requests before calling the service
5. **Handle Errors**: Implement `onStreamError` for proper error handling
6. **Test Thoroughly**: Write unit and integration tests

## Migration Guide

### From Manual SSE Implementation

1. Replace manual `HttpHandler` with `@RestController`
2. Replace manual SSE formatting with `SseEventStreamService`
3. Move event processing logic to `EventProcessor`
4. Use Spring Boot's `SseEmitter` instead of manual `OutputStream`

### Example Migration

**Before:**
```java
private void sendSSEEvent(OutputStream os, String event, String data) {
    os.write(("event: " + event + "\n").getBytes());
    os.write(("data: " + data + "\n\n").getBytes());
    os.flush();
}
```

**After:**
```java
@Override
public Optional<String> processEvent(Event event, Map<String, Object> context) {
    return Optional.of(event.toJson());
}
```

## Performance Considerations

- **Concurrent Requests**: Service handles multiple concurrent SSE connections
- **Memory**: Events are streamed, not buffered (unless processor accumulates)
- **Timeout**: Default 1 hour, adjust based on use case
- **Executor**: Uses cached thread pool for efficient resource usage

## Troubleshooting

### Events Not Received

- Check if processor is filtering events (returning `Optional.empty()`)
- Verify `RunConfig` has `StreamingMode.SSE`
- Check client SSE connection

### Timeout Issues

- Increase timeout: `streamEvents(..., customTimeoutMs)`
- Check network connectivity
- Verify agent is producing events

### Memory Issues

- Ensure processors don't accumulate too many events
- Use streaming mode, not accumulation mode
- Check for memory leaks in custom processors

## Contributing

When adding new features:
1. Follow existing code style
2. Add comprehensive tests
3. Update documentation
4. Add examples if introducing new patterns

## License

Copyright 2025 Google LLC  
Licensed under the Apache License, Version 2.0
