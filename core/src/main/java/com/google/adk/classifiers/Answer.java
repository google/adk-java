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

package com.google.adk.classifiers;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.adk.annotations.Experimental;

/**
 * A {@link BaseClassifier}'s answer to a {@link Question}: a {@link ChoiceAnswer}, a {@link
 * BinaryAnswer}, a {@link ScoreAnswer}, or a {@link RefusalAnswer}. The ADK object mapper reads and
 * writes answers in Python ADK's JSON form, which names each kind in a {@code type} field.
 */
@Experimental
@JsonSerialize(using = ClassifierJson.AnswerSerializer.class)
@JsonDeserialize(using = ClassifierJson.AnswerDeserializer.class)
public sealed interface Answer permits ChoiceAnswer, BinaryAnswer, ScoreAnswer, RefusalAnswer {}
