# Agent Development Kit (ADK) for Java

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.google.adk/google-adk)](https://search.maven.org/artifact/com.google.adk/google-adk)
[![r/agentdevelopmentkit](https://img.shields.io/badge/Reddit-r%2Fagentdevelopmentkit-FF4500?style=flat&logo=reddit&logoColor=white)](https://www.reddit.com/r/agentdevelopmentkit/)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/google/adk-java)

[ [English](https://github.com/google/adk-java/blob/main/README.md) | 简体中文 ]

<html>
    <h2 align="center">
      <img src="https://raw.githubusercontent.com/google/adk-python/main/assets/agent-development-kit.png" width="256"/>
    </h2>
    <h3 align="center">
      开源、代码优先的 Java 工具包，助您灵活自如地构建、评估与部署前沿 AI 智能体。
    </h3>
    <h3 align="center">
      重要链接：
      <a href="https://google.github.io/adk-docs/">官方文档</a> &
      <a href="https://github.com/google/adk-samples">示例集</a> &
      <a href="https://github.com/google/adk-python">Python ADK</a>.
    </h3>
</html>

智能体开发套件（Agent Development Kit，简称 ADK）专为希望在构建与 Google Cloud 服务紧密集成的先进 AI 智能体时获得精细控制和高度灵活性的开发者打造。它允许您直接在代码中定义智能体行为、编排逻辑与工具调用，从而在任何环境——从本地笔记本电脑到云端生产环境——实现强大的调试、版本控制与部署能力。

--------------------------------------------------------------------------------

## ✨ 核心特性

-   **丰富的工具生态**：利用预构建工具、自定义函数、OpenAPI 规范，或集成现有工具赋予智能体多样化能力，全面紧密集成 Google 生态系统。

-   **代码优先开发**：直接使用 Java 定义智能体业务逻辑、工具与编排，获得极致的灵活性、可测试性与版本管理能力。

-   **模块化多智能体系统**：通过将多个专业化智能体组合成灵活的层级架构，设计具备高扩展性的应用程序。

## 🚀 安装

如果您使用 Maven，请在 `pom.xml` 的依赖项中添加以下配置：

<!-- x-release-please-released-start-version -->

```xml
<dependency>
  <groupId>com.google.adk</groupId>
  <artifactId>google-adk</artifactId>
  <version>1.14.0</version>
</dependency>
<!-- Dev UI -->
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-dev</artifactId>
    <version>1.14.0</version>
</dependency>
```

<!-- x-release-please-released-end -->

若要使用未发布的开发版本，可以使用 <https://jitpack.io/#google/adk-java/>；具体用法示例请参阅 <https://github.com/enola-dev/LearningADK#jitpack>。

## 📚 官方文档

有关构建、评估与部署智能体的详细说明，请参阅 Java 官方文档与示例：

*   **[官方文档 (Documentation)](https://google.github.io/adk-docs)**
*   **[代码示例 (Samples)](https://github.com/google/adk-samples)**

## 🏁 特性亮点

### 与 Python ADK 相同的功能与熟悉的接口：

```java
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.GoogleSearchTool;

LlmAgent rootAgent = LlmAgent.builder()
    .name("search_assistant")
    .description("An assistant that can search the web.")
    .model("gemini-2.0-flash") // 或您偏好的模型
    .instruction("You are a helpful assistant. Answer user questions using Google Search when needed.")
    .tools(new GoogleSearchTool())
    .build();
```

### 开发控制台 (Development UI)

与备受喜爱的 Python 开发控制台体验一致。内置可视化开发界面，助您轻松测试、评估、调试及演示您的智能体。
<img src="https://raw.githubusercontent.com/google/adk-python/main/assets/adk-web-dev-ui-function-call.png"/>

### 智能体评估 (Evaluate Agents)

即将推出...

## 🤖 A2A 与 ADK 集成

对于远程智能体间通信（Agent-to-Agent），ADK 深度集成了 [A2A 协议](https://github.com/google/A2A/)。
端到端配置指南与示例命令请参阅 `a2a/README.md`。

## 🤝 贡献指南

我们热烈欢迎来自社区的贡献！无论是 Bug 报告、功能建议、文档改进还是代码贡献，都请先阅读我们的[**贡献指南**](./CONTRIBUTING.md)了解如何开始。

## 📄 许可证

本项目基于 Apache 2.0 许可证开源 —— 详情请参阅 [LICENSE](LICENSE) 文件。

## 预览阶段说明 (Preview)

本功能受[特定服务条款](https://cloud.google.com/terms/service-terms#1)中通用服务条款部分的“通用发布前提供服务条款 (Pre-GA Offerings Terms)”约束。Pre-GA 阶段的功能按“原样 (as is)”提供，获得的支持可能有限。更多信息请参阅[产品发布阶段说明](https://cloud.google.com/products?hl=en#product-launch-stages)。

--------------------------------------------------------------------------------

*祝您智能体构建愉快！*

---

> **翻译说明（Translation Disclaimer）**
> 本文档由社区志愿者（[@JasonYeYuhe](https://github.com/JasonYeYuhe)）人工翻译并维护，旨在降低中文开发者的阅读门槛。最后同步日期：2026年11月14日。若译文与英文原版存在任何出入或滞后，请以 [英文原版 (README.md)](https://github.com/google/adk-java/blob/main/README.md) 为准。欢迎通过 PR 协助改进译文！
