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
package com.google.mu.util.graph;

import static com.google.mu.util.stream.MoreStreams.whileNotEmpty;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Implements generic graph and tree traversal algorithms ({@link #preOrderFrom pre-order},
 * {@link #postOrderFrom post-order} and {@link #breadthFirstFrom breadth-first}) as lazily
 * evaluated streams, allowing infinite-size graphs.
 *
 * <p>None of these streams are safe to run in parallel.
 *
 * @since 3.9
 */
public final class Walker<N> {
  private final Supplier<Traversal<N>> newTraversal;

  Walker(Supplier<Traversal<N>> newTraversal) {
    this.newTraversal = newTraversal;
  }

  /**
   * Returns a {@code Walker} to walk the tree structure (no cycles) as observed by the {@code
   * findChildren} function, which finds children of any given tree node.
   *
   * <p>The returned object is idempotent, stateless and immutable as long as {@code findChildren} is
   * idempotent, stateless and immutable.
   *
   * <p>WARNING: the returned {@code Walker} can walk in cycles if {@code findChildren} turns out
   * cyclic (like, any undirected graph).
   *
   * @param findChildren Function to get the child nodes for a given node.
   *        No children if empty stream or null is returned,
   */
  public static <N> Walker<N> inTree(
      Function<? super N, ? extends Stream<? extends N>> findChildren) {
    return inGraph(findChildren, n -> true);
  }

  /**
   * Returns a {@code Walker} to walk the graph structure (possibly with cycles) as observed by
   * the {@code findSuccessors} function, which finds successors of any given graph node.
   *
   * <p>Because the traversal needs to remember which node(s) have been traversed, memory usage is
   * linear to the number of traversed nodes.
   *
   * @param findSuccessors Function to get the successor nodes for a given node.
   *        No successor if empty stream or null is returned,
   */
  public static <N> Walker<N> inGraph(
      Function<? super N, ? extends Stream<? extends N>> findSuccessors) {
    requireNonNull(findSuccessors);
    return new Walker<>(() -> new Traversal<>(findSuccessors, new HashSet<>()::add));
  }

  /**
   * Similar to {@link #inGraph(Function)}, returns a {@code Walker} that can be used to
   * traverse a graph of nodes. {@code tracker} is used to track every node being traversed. When
   * {@code Walker} is about to traverse a node, {@code tracker.test(node)} will be called and the
   * node will be skipped if false is returned.
   *
   * <p>This is useful for custom node tracking. For example, the caller could use a {@link
   * java.util.TreeSet} or some functional equivalence to compare nodes using custom equality or
   * equivalence; or, use a {@link java.util.ConcurrentHashMap} if multiple threads need to walk the
   * same graph concurrently and collaboratively:
   *
   * <pre>{@code
   * Walker<Room> concurrentWalker =
   *     Walker.inGraph(buildingMap, ConcurrentHashMap.newKeySet()::add);
   *
   * // thread 1:
   * Stream<Room> shield = concurrentWalker.preOrderFrom(roof);
   * shield.forEachOrdered(room -> ...);
   *
   * // thread 2:
   * Stream<Room> avengers = concurrentWalker.breadthFirstFrom(mainEntrance);
   * avengers.forEachOrdered(room -> ...);
   * }</pre>
   *
   * <p>In the case of walking a very large graph with more nodes than can fit in memory, it's
   * conceivable to use {@link com.google.common.hash.BloomFilter#put Bloom filter} to track visited
   * nodes, as long as you are okay with probabilistically missing a fraction of the graph nodes due
   * to Bloom filter's inherent false-positive rates. Because Bloom filters have zero
   * false-negatives, it's guaranteed that the walker will never walk in cycles.
   *
   * @param findSuccessors Function to get the successor nodes for a given node.
   *        No successor if empty stream or null is returned,
   * @param tracker Tracks each node being visited during traversal. Returns false if the node
   *        should be skipped for traversal (for example because it has already been traversed).
   *        Despite being a {@link Predicate}, the tracker typically carries side-effects like
   *        storing the tracked node in a set ({@code set::add} will do).
   */
  public static <N> Walker<N> inGraph(
      Function<? super N, ? extends Stream<? extends N>> findSuccessors,
      Predicate<? super N> tracker) {
    requireNonNull(findSuccessors);
    requireNonNull(tracker);
    return new Walker<>(() -> new Traversal<>(findSuccessors, tracker));
  }

  /**
   * Starts from {@code startNodes} and walks depth first in pre-order by using {@code
   * findSuccessors} function iteratively.
   *
   * <p>The returned stream may be infinite if the graph has infinite depth or infinite breadth, or
   * both. The stream can still be short-circuited to consume a limited number of nodes during
   * traversal.
   */
  @SafeVarargs
  public final Stream<N> preOrderFrom(N... startNodes) {
    return newTraversal.get().preOrder(nonNullList(startNodes));
  }

  /**
   * Starts from {@code startNodes} and walks depth first in pre-order.
   *
   * <p>The returned stream may be infinite if the graph has infinite depth or infinite breadth, or
   * both. The stream can still be short-circuited to consume a limited number of nodes during
   * traversal.
   */
  public final Stream<N> preOrderFrom(Iterable<? extends N> startNodes) {
    return newTraversal.get().preOrder(startNodes);
  }

  /**
   * Starts from {@code startNodes} and walks depth first in post-order.
   *
   * <p>The returned stream may be infinite if the graph has infinite breadth. The stream can still
   * be short-circuited to consume a limited number of nodes during traversal.
   *
   * <p>The stream may result in infinite loop when it traversing through a node with infinite
   * depth.
   */
  @SafeVarargs
  public final Stream<N> postOrderFrom(N... startNodes) {
    return newTraversal.get().postOrder(nonNullList(startNodes));
  }

  /**
   * Starts from {@code startNodes} and walks depth first in post-order.
   *
   * <p>The returned stream may be infinite if the graph has infinite breadth. The stream can still
   * be short-circuited to consume a limited number of nodes during traversal.
   *
   * <p>The stream may result in infinite loop when it traversing through a node with infinite
   * depth.
   */
  public final Stream<N> postOrderFrom(Iterable<? extends N> startNodes) {
    return newTraversal.get().postOrder(startNodes);
  }

  /**
   * Starts from {@code startNodes} and walks in breadth-first order.
   *
   * <p>The returned stream may be infinite if the graph has infinite depth or infinite breadth, or
   * both. The stream can still be short-circuited to consume a limited number of nodes during
   * traversal.
   */
  @SafeVarargs
  public final Stream<N> breadthFirstFrom(N... startNodes) {
    return newTraversal.get().breadthFirst(nonNullList(startNodes));
  }

  /**
   * Starts from {@code startNodes} and walks in breadth-first order.
   *
   * <p>The returned stream may be infinite if the graph has infinite depth or infinite breadth, or
   * both. The stream can still be short-circuited to consume a limited number of nodes during
   * traversal.
   */
  public final Stream<N> breadthFirstFrom(Iterable<? extends N> startNodes) {
    return newTraversal.get().breadthFirst(startNodes);
  }

  /**
   * Detects whether the graph structure as observed by the {@code findSuccessors} function has
   * cycles, by walking from {@code startNode}.
   *
   * <p>This method will hang if the given graph is infinite without cycle (the sequence of natural
   * numbers for instance).
   *
   * @param startNode the node to start walking the graph.
   * @param findSuccessors the function to find successors of any given node.
   * @return The stream of nodes starting from {@code startNode} ending with nodes along a cyclic
   *         path. The last node will also be the starting point of the cycle.
   *         That is, if {@code A} and {@code B} form a cycle, the stream ends with
   *         {@code A -> B -> A}. If there is no cycle, {@link Stream#empty} is returned.
   */
  public static <N> Stream<N> detectCycleFrom(
      N startNode, Function<? super N, ? extends Stream<? extends N>> findSuccessors) {
    AtomicReference<N> cyclic = new AtomicReference<>();
    Set<N> seen = new HashSet<>();
    LinkedHashSet<N> stack = new LinkedHashSet<>();
    Walker<N> walker = inGraph(findSuccessors, new Predicate<N>() {
      @Override public boolean test(N node) {
        if (seen.add(node)) {
          stack.add(node);
          return true;
        } else {
          if (stack.contains(node)) {
            cyclic.compareAndSet(null, node);
          }
          return false;
        }
      }
    });
    return walker.postOrderFrom(startNode)
        .peek(stack::remove)
        .filter(n -> cyclic.get() != null)
        .findFirst()
        .map(last -> Stream.concat(stack.stream(), Stream.of(last, cyclic.get())))
        .orElse(Stream.empty());
  }

  private static final class Traversal<N> implements Consumer<N> {
    private final Function<? super N, ? extends Stream<? extends N>> findSuccessors;
    private final Predicate<? super N> tracker;
    private final Deque<Spliterator<? extends N>> horizon = new ArrayDeque<>();
    private N visited;

    Traversal(
        Function<? super N, ? extends Stream<? extends N>> findSuccessors,
        Predicate<? super N> tracker) {
      this.findSuccessors = findSuccessors;
      this.tracker = tracker;
    }

    @Override
    public void accept(N value) {
      this.visited = requireNonNull(value);
    }

    Stream<N> breadthFirst(Iterable<? extends N> startNodes) {
      horizon.add(startNodes.spliterator());
      return topDown(Queue::add);
    }

    Stream<N> preOrder(Iterable<? extends N> startNodes) {
      horizon.push(startNodes.spliterator());
      return topDown(Deque::push);
    }

    Stream<N> postOrder(Iterable<? extends N> startNodes) {
      horizon.push(startNodes.spliterator());
      Deque<N> roots = new ArrayDeque<>();
      return whileNotEmpty(horizon).map(h -> removeFromBottom(roots)).filter(Objects::nonNull);
    }

    private Stream<N> topDown(InsertionOrder order) {
      return whileNotEmpty(horizon).map(h -> removeFromTop(order)).filter(Objects::nonNull);
    }

    private N removeFromTop(InsertionOrder traversalOrder) {
      do {
        if (visitNext()) {
          N next = visited;
          Stream<? extends N> successors = findSuccessors.apply(next);
          if (successors != null) {
            traversalOrder.insertInto(horizon, successors.spliterator());
          }
          return next;
        }
      } while (!horizon.isEmpty());
      return null; // no more element
    }

    private N removeFromBottom(Deque<N> roots) {
      while (visitNext()) {
        N next = visited;
        Stream<? extends N> successors = findSuccessors.apply(next);
        if (successors == null) {
          return next;
        }
        horizon.push(successors.spliterator());
        roots.push(next);
      }
      return roots.pollFirst();
    }

    private boolean visitNext() {
      Spliterator<? extends N> top = horizon.getFirst();
      while (top.tryAdvance(this)) {
        if (tracker.test(visited)) {
          return true;
        }
      }
      horizon.removeFirst();
      return false;
    }
  }

  @SafeVarargs
  private static <N> List<N> nonNullList(N... values) {
    return Arrays.stream(values).peek(Objects::requireNonNull).collect(toList());
  }

  private interface InsertionOrder {
    <N> void insertInto(Deque<N> deque, N value);
  }
}
