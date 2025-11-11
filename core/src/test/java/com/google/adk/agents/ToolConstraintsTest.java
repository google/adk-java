package com.google.adk.agents;

// 导入项目中的类
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BuiltInTool;
import com.google.adk.agents.ToolConstraintViolationException;

// 导入 Guava 库
import com.google.common.collect.ImmutableList;

// 导入 JUnit 5 和 Mockito
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer; // 【修正】: 导入 Answer 接口
import org.mockito.invocation.InvocationOnMock; // 【修正】: 导入 InvocationOnMock

// 导入 Java 标准库
import java.util.List;
import java.util.ArrayList;

// 导入 Mockito 和 JUnit 5 的静态方法
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

// 导入 Google Truth 断言
import static com.google.common.truth.Truth.assertThat;

/**
 * 针对 ToolConstraints 校验器的单元测试。
 * (V7 - 使用 Lambda 表达式直接实现 Answer 接口，解决泛型和方法解析问题)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ToolConstraintsTest {

    @Mock
    private LlmAgent mockRootAgent;

    @Mock
    private LlmAgent mockSubAgent;

    private BaseTool mockRegularTool;
    private BaseTool mockBuiltInTool1;
    private BaseTool mockBuiltInTool2;

    @BeforeEach
    void setUp() {
        mockRegularTool = mock(BaseTool.class);
        mockBuiltInTool1 = mock(BaseTool.class, withSettings().extraInterfaces(BuiltInTool.class));
        mockBuiltInTool2 = mock(BaseTool.class, withSettings().extraInterfaces(BuiltInTool.class));
    }

    // --- 开始测试“五种典型场景” ---

    @Test
    void validate_agentTreeWithOnlyRegularTools_shouldPass() {
        // 【FIX V7】: 使用 Lambda 表达式直接返回 List<BaseAgent>
        when(mockRootAgent.subAgents()).thenAnswer(
                invocation -> new ArrayList<>(List.of(mockSubAgent))
        );
        when(mockRootAgent.tools()).thenReturn(List.of(mockRegularTool));
        when(mockSubAgent.tools()).thenReturn(List.of(mockRegularTool));

        assertDoesNotThrow(
                () -> ToolConstraints.validate(mockRootAgent),
                "一个只包含普通工具的 Agent 树应该被视为合法"
        );
    }

    @Test
    void validate_rootAgentWithSingleBuiltInTool_shouldPass() {
        // 【FIX V7】: 使用 Lambda 表达式直接返回 List.of() (空列表)
        when(mockRootAgent.subAgents()).thenAnswer(invocation -> List.of());
        when(mockRootAgent.tools()).thenReturn(List.of(mockBuiltInTool1));

        assertDoesNotThrow(
                () -> ToolConstraints.validate(mockRootAgent),
                "根 Agent 持有单个内置工具是合法的"
        );
    }

    @Test
    void validate_agentWithBuiltInAndRegularTools_shouldThrowA1_MixAndMatch() {
        when(mockRootAgent.tools()).thenReturn(List.of(mockBuiltInTool1, mockRegularTool));
        when(mockRootAgent.subAgents()).thenAnswer(invocation -> List.of()); // 模拟子 Agent 返回

        ToolConstraintViolationException ex = assertThrows(
                ToolConstraintViolationException.class,
                () -> ToolConstraints.validate(mockRootAgent),
                "A1.1 约束：内置工具和普通工具不能混用"
        );

        assertThat(ex.getMessage()).contains("违反 A1 约束：内置工具 (BuiltInTool) 不能与普通工具 (Regular Tool) 混用");
    }

    @Test
    void validate_agentWithMultipleBuiltInTools_shouldThrowA1_MultipleBuiltIn() {
        when(mockRootAgent.tools()).thenReturn(List.of(mockBuiltInTool1, mockBuiltInTool2));
        when(mockRootAgent.subAgents()).thenAnswer(invocation -> List.of()); // 模拟子 Agent 返回

        ToolConstraintViolationException ex = assertThrows(
                ToolConstraintViolationException.class,
                () -> ToolConstraints.validate(mockRootAgent),
                "A1.2 约束：一个 Agent 最多只能有一个内置工具"
        );

        assertThat(ex.getMessage()).contains("一个 Agent 最多只能有一个内置工具");
    }

    @Test
    void validate_subAgentWithBuiltInTool_shouldThrowA2_SubAgentViolation() {
        // 安排 (Arrange)
        when(mockRootAgent.tools()).thenReturn(List.of(mockBuiltInTool1));
        // 【FIX V7】: 使用 Lambda 表达式直接返回 List<BaseAgent> 类型
        when(mockRootAgent.subAgents()).thenAnswer(
                invocation -> new ArrayList<>(List.of(mockSubAgent))
        );

        // 子 Agent 是非法的
        when(mockSubAgent.tools()).thenReturn(List.of(mockBuiltInTool2));

        // 行动 (Act) & 断言 (Assert)
        ToolConstraintViolationException ex = assertThrows(
                ToolConstraintViolationException.class,
                () -> ToolConstraints.validate(mockRootAgent),
                "A2 约束：子 Agent 禁止使用内置工具"
        );

        assertThat(ex.getMessage()).contains("违反 A2 约束：内置工具 (BuiltInTool) 只能在根 Agent (Root Agent) 上定义");
    }
}