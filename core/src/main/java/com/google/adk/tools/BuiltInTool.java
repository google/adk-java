/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.tools;

/**
 * 标记接口 (Marker Interface)，用于标识一个工具是“内置工具”。
 *
 * <p>任何实现了此接口的 {@link BaseTool} 都会被 {@code ToolConstraints}
 * 校验逻辑识别，并受到 A1/A2 约束的限制。
 */
public interface BuiltInTool {
    // 这是一个标记接口，不需要任何方法。
}