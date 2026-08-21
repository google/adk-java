/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.plugins.agentanalytics;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class GcsOffloaderTest {
  private static final String PROJECT_ID = "test-project";
  private static final String BUCKET_NAME = "test-bucket";
  private static final String PATH = "test-path/file.txt";
  private static final String CONTENT_TYPE = "text/plain";

  private Storage mockStorage;
  private ExecutorService executor;
  private GcsOffloader gcsOffloader;

  @Before
  public void setUp() {
    mockStorage = mock(Storage.class);
    executor = Executors.newSingleThreadExecutor();
    gcsOffloader = new GcsOffloader(PROJECT_ID, BUCKET_NAME, executor, null, mockStorage);
  }

  @After
  public void tearDown() {
    executor.shutdown();
  }

  @Test
  public void uploadContent_bytes_succeeds() throws Exception {
    byte[] data = "hello world".getBytes(UTF_8);
    CompletableFuture<String> future = gcsOffloader.uploadContent(data, CONTENT_TYPE, PATH);

    String result = future.get();

    assertEquals("gs://" + BUCKET_NAME + "/" + PATH, result);

    ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
    verify(mockStorage).create(blobInfoCaptor.capture(), any(byte[].class));

    BlobInfo blobInfo = blobInfoCaptor.getValue();
    assertEquals(BlobId.of(BUCKET_NAME, PATH), blobInfo.getBlobId());
    assertEquals(CONTENT_TYPE, blobInfo.getContentType());
  }

  @Test
  public void uploadContent_string_succeeds() throws Exception {
    String data = "hello world string";
    CompletableFuture<String> future = gcsOffloader.uploadContent(data, CONTENT_TYPE, PATH);

    String result = future.get();

    assertEquals("gs://" + BUCKET_NAME + "/" + PATH, result);

    ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
    verify(mockStorage).create(blobInfoCaptor.capture(), any(byte[].class));

    BlobInfo blobInfo = blobInfoCaptor.getValue();
    assertEquals(BlobId.of(BUCKET_NAME, PATH), blobInfo.getBlobId());
    assertEquals(CONTENT_TYPE, blobInfo.getContentType());
  }

  @Test
  public void uploadContent_executorRejected_returnsFailedFuture() {
    Executor rejectingExecutor =
        r -> {
          throw new RejectedExecutionException("Rejected");
        };
    GcsOffloader offloaderWithRejectingExecutor =
        new GcsOffloader(PROJECT_ID, BUCKET_NAME, rejectingExecutor, null, mockStorage);

    CompletableFuture<String> future =
        offloaderWithRejectingExecutor.uploadContent("data".getBytes(UTF_8), CONTENT_TYPE, PATH);

    assertTrue(future.isCompletedExceptionally());
    assertThrows(ExecutionException.class, future::get);
  }

  @Test
  public void close_doesNotCloseStorageOverride() throws Exception {
    gcsOffloader.close();
    verify(mockStorage, never()).close();
  }
}
