/*****************************************************************************
 * ------------------------------------------------------------------------- *
 * Licensed under the Apache License, Version 2.0 (the "License");           *
 * you may not use this file except in compliance with the License.          *
 * You may obtain a copy of the License at                                   *
 *                                                                           *
 * http://www.apache.org/licenses/LICENSE-2.0                                *
 *                                                                           *
 * Unless required by applicable law or agreed to in writing, software       *
 * distributed under the License is distributed on an "AS IS" BASIS,         *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 * See the License for the specific language governing permissions and       *
 * limitations under the License.                                            *
 *****************************************************************************/
package com.google.mu.util.algo.graph;

import static com.google.common.truth.Truth.assertThat;
import static com.google.mu.util.stream.MoreStreams.indexesFrom;
import static java.util.stream.Collectors.toList;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.google.common.testing.ClassSanityTester;
import com.google.common.testing.NullPointerTester;

public class TraversalTest {
  private final MutableGraph<String> graph = GraphBuilder.undirected().<String>build();

  @Test
  public void preOrder_noChildren() {
    graph.addNode("root");
    assertThat(preOrder("root").collect(toList())).containsExactly("root");
  }

  @Test
  public void preOrder_oneEdge() {
    graph.putEdge("foo", "bar");
    assertThat(preOrder("foo").collect(toList())).containsExactly("foo", "bar").inOrder();
  }

  @Test
  public void preOrder_twoEdges() {
    graph.putEdge("foo", "bar");
    graph.putEdge("bar", "baz");
    assertThat(preOrder("foo").collect(toList())).containsExactly("foo", "bar", "baz").inOrder();
  }

  public void testPreOrder_depthFirst() {
    graph.putEdge("foo", "bar");
    graph.putEdge("foo", "baz");
    graph.putEdge("foo", "cat");
    graph.putEdge("cat", "run");
    graph.putEdge("bar", "dog");
    graph.putEdge("bar", "cat");
    assertThat(preOrder("foo").collect(toList()))
        .containsExactly("foo", "bar", "cat", "run", "dog", "baz").inOrder();
  }

  @Test
  public void preOrder_infinite() {
    Stream<Integer> stream = Traversal.<Integer>forGraph(n -> indexesFrom(n + 1)).preOrderFrom(1);
    assertThat(stream.limit(4).collect(toList()))
        .containsExactly(1, 2, 3, 4)
        .inOrder();
  }

  @Test
  public void postOrder_noChildren() {
    graph.addNode("root");
    assertThat(postOrder("root").collect(toList())).containsExactly("root");
  }

  @Test
  public void postOrder_oneEdge() {
    graph.putEdge("foo", "bar");
    assertThat(postOrder("foo").collect(toList())).containsExactly("bar", "foo").inOrder();
  }

  @Test
  public void postOrder_twoEdges() {
    graph.putEdge("foo", "bar");
    graph.putEdge("bar", "baz");
    assertThat(postOrder("foo").collect(toList())).containsExactly("baz", "bar", "foo").inOrder();
  }

  @Test
  public void postOrder_depthFirst() {
    graph.putEdge("foo", "bar");
    graph.putEdge("foo", "baz");
    graph.putEdge("bar", "dog");
    assertThat(postOrder("foo").collect(toList())).containsExactly("dog", "bar", "baz", "foo")
        .inOrder();
  }

  @Test
  public void postOrder_infinite() {
    Map<Integer, Stream<Integer>> infiniteWidth =
        ImmutableMap.of(1, indexesFrom(2), 2, Stream.of(3, 4), 3, Stream.of(4, 5), 5, Stream.empty());
    Stream<Integer> stream = Traversal.forGraph(infiniteWidth::get).postOrderFrom(1);
    assertThat(stream.limit(6).collect(toList()))
        .containsExactly(4, 5, 3, 2, 6, 7)
        .inOrder();
  }

  @Test
  public void breadthFirst_noChildren() {
    graph.addNode("root");
    assertThat(bfs("root").collect(toList())).containsExactly("root");
  }

  @Test
  public void breadthFirst_oneEdge() {
    graph.putEdge("foo", "bar");
    assertThat(bfs("foo").collect(toList())).containsExactly("foo", "bar").inOrder();
  }

  @Test
  public void breadthFirst_twoEdges() {
    graph.putEdge("foo", "bar");
    graph.putEdge("bar", "baz");
    assertThat(bfs("foo").collect(toList())).containsExactly("foo", "bar", "baz").inOrder();
  }

  @Test
  public void breadthFirst_breadthFirst() {
    graph.putEdge("foo", "bar");
    graph.putEdge("foo", "baz");
    graph.putEdge("bar", "dog");
    assertThat(bfs("foo").collect(toList())).containsExactly("foo", "bar", "baz", "dog")
        .inOrder();
  }

  @Test
  public void breadthFirst_infinite() {
    Stream<Integer> stream =
        Traversal.<Integer>forGraph(n -> Stream.of(n + 1, n + 2)).breadthFirstFrom(1);
    assertThat(stream.limit(6).collect(toList()))
        .containsExactly(1, 2, 3, 4, 5, 6)
        .inOrder();
  }

  @Test public void testNulls() throws Exception {
    new NullPointerTester().testAllPublicStaticMethods(Traversal.class);
    new ClassSanityTester().forAllPublicStaticMethods(Traversal.class).testNulls();
  }

  private Stream<String> preOrder(String firstNode) {
    return Traversal.<String>forGraph(n -> graph.adjacentNodes(n).stream().sorted())
        .preOrderFrom(firstNode);
  }

  private Stream<String> postOrder(String firstNode) {
    return Traversal.<String>forGraph(n -> graph.adjacentNodes(n).stream().sorted())
        .postOrderFrom(firstNode);
  }

  private Stream<String> bfs(String firstNode) {
    return Traversal.<String>forGraph(n -> graph.adjacentNodes(n).stream().sorted())
        .breadthFirstFrom(firstNode);
  }
}
