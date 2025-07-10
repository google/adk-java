package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.ReadonlyContext;
import com.google.common.collect.ImmutableList;
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
          public Flowable<List<BaseTool>> getTools(ReadonlyContext readonlyContext) {
            return Flowable.just(ImmutableList.of(mockTool1, mockTool2));
          }

          @Override
          public void close() throws Exception {}
        };

    List<BaseTool> tools = toolset.getTools(mockContext).blockingFirst();
    assertThat(tools).containsExactly(mockTool1, mockTool2);
  }
}
