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

package com.google.adk.workflow;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EdgeTest {

  private final Node from = new StubNode("from");
  private final Node to = new StubNode("to");

  @Test
  public void constructor_withoutRoutes_createsAnUnconditionalEdge() {
    Edge edge = new Edge(from, to);

    assertThat(edge.from()).isSameInstanceAs(from);
    assertThat(edge.to()).isSameInstanceAs(to);
    assertThat(edge.routes()).isEmpty();
    assertThat(edge.isUnconditional()).isTrue();
  }

  @Test
  public void constructor_withOneRoute_createsAnEdgeSelectedByIt() {
    Route route = new Route.Tag("approve");

    Edge edge = new Edge(from, to, route);

    assertThat(edge.routes()).containsExactly(route);
    assertThat(edge.isUnconditional()).isFalse();
    assertThat(edge).isEqualTo(new Edge(from, to, ImmutableList.of(route)));
  }

  @Test
  public void constructor_rejectsANullFrom() {
    assertThrows(NullPointerException.class, () -> new Edge(null, to));
  }

  @Test
  public void constructor_rejectsANullTo() {
    assertThrows(NullPointerException.class, () -> new Edge(from, null));
  }

  @Test
  public void constructor_rejectsANullRoute() {
    assertThrows(NullPointerException.class, () -> new Edge(from, to, (Route) null));
  }

  @Test
  public void constructor_rejectsANullRouteList() {
    assertThrows(NullPointerException.class, () -> new Edge(from, to, (ImmutableList<Route>) null));
  }
}
