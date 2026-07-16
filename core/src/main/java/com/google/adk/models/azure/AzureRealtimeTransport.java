package com.google.adk.models.azure;

import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure transport implementation for the WebSocket-based Realtime API.
 *
 * <p>Handles bidirectional audio/text streaming via persistent WebSocket connections. For
 * non-realtime models, see {@link AzureRestTransport}.
 */
public final class AzureRealtimeTransport implements AzureTransport {

  private static final Logger logger = LoggerFactory.getLogger(AzureRealtimeTransport.class);

  @Override
  public boolean supports(String modelName) {
    return AzureModelUtils.isRealtimeModel(modelName);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request, AzureConfig config) {
    return new AzureRealtimeLlmConnection(config, request);
  }

  /**
   * For realtime models, {@code generateContent} is not the primary interaction mode. This provides
   * a minimal fallback that opens a short-lived WebSocket, sends the last user content, and
   * collects responses.
   */
  @Override
  public Flowable<LlmResponse> generateContent(
      LlmRequest request, AzureConfig config, boolean stream) {
    return Flowable.create(
        emitter -> {
          AzureRealtimeLlmConnection conn = null;
          final Disposable[] subscription = new Disposable[1];
          try {
            conn = new AzureRealtimeLlmConnection(config, request);
            final AzureRealtimeLlmConnection activeConn = conn;

            subscription[0] =
                conn.receive()
                    .takeUntil(
                        response ->
                            response.turnComplete().orElse(false)
                                || response.errorMessage().isPresent())
                    .doOnNext(emitter::onNext)
                    .doOnError(
                        error -> {
                          if (!emitter.isCancelled()) {
                            emitter.onError(error);
                          }
                        })
                    .doOnComplete(
                        () -> {
                          if (!emitter.isCancelled()) {
                            emitter.onComplete();
                          }
                        })
                    .doFinally(activeConn::close)
                    .subscribe();

            emitter.setCancellable(
                () -> {
                  if (subscription[0] != null && !subscription[0].isDisposed()) {
                    subscription[0].dispose();
                  }
                  activeConn.close();
                });

            Optional<Content> lastUserContent =
                request.contents().isEmpty()
                    ? Optional.empty()
                    : Optional.of(request.contents().get(request.contents().size() - 1));

            if (lastUserContent.isPresent()) {
              conn.sendContent(lastUserContent.get()).blockingAwait();
            } else {
              conn.sendContent(Content.fromParts(Part.fromText(""))).blockingAwait();
            }
          } catch (Exception e) {
            logger.error("Error in AzureRealtimeTransport.generateContent", e);
            if (!emitter.isCancelled()) {
              emitter.onError(e);
            }
            if (conn != null) {
              conn.close(e);
            }
          }
        },
        BackpressureStrategy.BUFFER);
  }
}
