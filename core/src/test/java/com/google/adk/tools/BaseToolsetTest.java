/*
 * Copyright 2025 Google LLC
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

package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.ReadonlyContext;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BaseToolsetTest {

  @Test
  public void testGetTools() {
    BaseTool mockTool1 = mock(BaseTool.class);
    BaseTool mockTool2 = mock(BaseTool.class);
    ReadonlyContext mockContext = mock(ReadonlyContext.class);

    BaseToolset toolset =
        new BaseToolset() {
          @Override
          public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
            return Flowable.just(mockTool1, mockTool2);
          }

          @Override
          public void close() throws Exception {}
        };

    List<BaseTool> tools = toolset.getTools(mockContext).toList().blockingGet();
    assertThat(tools).containsExactly(mockTool1, mockTool2);
  }
}
