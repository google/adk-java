package com.google.adk.webservice;

import com.google.adk.a2a.A2ASendMessageExecutor;
import com.google.adk.a2a.ResponseConverter;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Core service that bridges the A2A JSON-RPC sendMessage API to a local ADK runner.
 *
 * @apiNote **EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do
 *     not use in production code.
 */
@Service
public class A2ARemoteService {

  private static final Logger logger = LoggerFactory.getLogger(A2ARemoteService.class);
  private static final int ERROR_CODE_INVALID_PARAMS = -32602;
  private static final int ERROR_CODE_INTERNAL_ERROR = -32603;

  private final A2ASendMessageExecutor executor;

  public A2ARemoteService(A2ASendMessageExecutor executor) {
    this.executor = executor;
  }

  public SendMessageResponse handle(SendMessageRequest request) {
    if (request == null) {
      logger.warn("Received null SendMessageRequest");
      return invalidParamsResponse(null, "Request body is missing");
    }

    MessageSendParams params = request.getParams();
    if (params == null) {
      logger.warn("SendMessageRequest {} missing params", request.getId());
      return invalidParamsResponse(request, "Request params are missing");
    }

    Message inbound = params.message();
    if (inbound == null) {
      logger.warn("SendMessageRequest {} missing message payload", request.getId());
      return invalidParamsResponse(request, "Request message payload is missing");
    }

    boolean generatedContext = inbound.getContextId() == null || inbound.getContextId().isEmpty();
    Message normalized = ensureContextId(inbound);
    if (generatedContext) {
      logger.debug("Incoming request lacked contextId; generated {}", normalized.getContextId());
    }

    try {
      Message result = executor.execute(normalized).blockingGet();
      if (result == null) {
        result =
            ResponseConverter.eventsToMessage(
                List.of(), normalized.getContextId(), normalized.getTaskId());
      }

      logger.debug("Returning A2A response for context {}", normalized.getContextId());
      return new SendMessageResponse(request.getId(), result);
    } catch (RuntimeException e) {
      logger.error("Failed to process remote A2A request", e);
      return errorResponse(request, e);
    }
  }

  private static Message ensureContextId(Message message) {
    if (message.getContextId() != null && !message.getContextId().isEmpty()) {
      return message;
    }
    return new Message.Builder(message).contextId(UUID.randomUUID().toString()).build();
  }

  private static SendMessageResponse invalidParamsResponse(
      SendMessageRequest request, String reason) {
    JSONRPCError error = new JSONRPCError(ERROR_CODE_INVALID_PARAMS, reason, null);
    return new SendMessageResponse(request != null ? request.getId() : null, error);
  }

  private static SendMessageResponse errorResponse(SendMessageRequest request, Throwable error) {
    String message = "Internal error processing sendMessage request";
    JSONRPCError jsonrpcError = new JSONRPCError(ERROR_CODE_INTERNAL_ERROR, message, null);
    return new SendMessageResponse(request != null ? request.getId() : null, jsonrpcError);
  }
}
