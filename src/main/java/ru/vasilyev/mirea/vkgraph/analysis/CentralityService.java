package ru.vasilyev.mirea.vkgraph.analysis;

import ru.vasilyev.mirea.vkgraph.domain.Edge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class CentralityService {
    private static final int EIGENVECTOR_MAX_ITERATIONS = 250;
    private static final double EIGENVECTOR_TOLERANCE = 1.0e-10;
    private static final long SAMPLE_SEED = 42L;

    public CentralityResult analyze(Set<Long> vertexIds, Set<Edge> edges,
                                    List<Long> seedIds, int requestedSamples) {
        List<Long> ids = vertexIds.stream().sorted().toList();
        int n = ids.size();
        Map<Long, Integer> index = new HashMap<>(Math.max(16, n * 2));
        for (int i = 0; i < n; i++) {
            index.put(ids.get(i), i);
        }
        int[][] adjacency = adjacency(n, edges, index);
        ComponentStats components = componentStats(adjacency);

        Set<Integer> seedIndexes = new HashSet<>();
        for (long seedId : seedIds) {
            Integer seedIndex = index.get(seedId);
            if (seedIndex != null) {
                seedIndexes.add(seedIndex);
            }
        }

        Map<Integer, ClosenessValue> closeness = new HashMap<>();
        for (int seedIndex : seedIndexes) {
            closeness.put(seedIndex, closeness(seedIndex, adjacency));
        }

        BetweennessValue betweenness = betweennessApproximation(
                adjacency, seedIndexes, Math.min(requestedSamples, Math.max(1, n)));
        EigenvectorValue eigenvector = eigenvector(adjacency);

        List<CentralityScore> scores = new ArrayList<>();
        for (long seedId : seedIds) {
            Integer seedIndex = index.get(seedId);
            if (seedIndex == null) {
                scores.add(new CentralityScore(seedId, 0, 0, 0, 0, 0));
                continue;
            }
            ClosenessValue close = closeness.get(seedIndex);
            scores.add(new CentralityScore(seedId,
                    betweenness.values()[seedIndex], close.value(),
                    eigenvector.values()[seedIndex], close.reachableNodes(),
                    adjacency[seedIndex].length));
        }
        return new CentralityResult(List.copyOf(scores), n, edges.size(),
                components.count(), components.largest(), betweenness.samples(),
                eigenvector.iterations(), eigenvector.converged());
    }

    private static int[][] adjacency(int n, Set<Edge> edges, Map<Long, Integer> index) {
        int[] degrees = new int[n];
        for (Edge edge : edges) {
            Integer a = index.get(edge.source());
            Integer b = index.get(edge.target());
            if (a != null && b != null) {
                degrees[a]++;
                degrees[b]++;
            }
        }
        int[][] result = new int[n][];
        for (int i = 0; i < n; i++) {
            result[i] = new int[degrees[i]];
        }
        int[] offsets = new int[n];
        for (Edge edge : edges) {
            Integer a = index.get(edge.source());
            Integer b = index.get(edge.target());
            if (a != null && b != null) {
                result[a][offsets[a]++] = b;
                result[b][offsets[b]++] = a;
            }
        }
        return result;
    }

    private static ClosenessValue closeness(int source, int[][] adjacency) {
        int[] distances = distances(source, adjacency);
        long sum = 0;
        int reachable = 0;
        for (int distance : distances) {
            if (distance >= 0) {
                reachable++;
                sum += distance;
            }
        }
        if (reachable <= 1 || sum == 0 || adjacency.length <= 1) {
            return new ClosenessValue(0, reachable);
        }
        double reachableMinusSelf = reachable - 1.0;
        double value = (reachableMinusSelf / sum)
                * (reachableMinusSelf / (adjacency.length - 1.0));
        return new ClosenessValue(value, reachable);
    }

    private static BetweennessValue betweennessApproximation(
            int[][] adjacency, Set<Integer> seedIndexes, int samples) {
        int n = adjacency.length;
        double[] centrality = new double[n];
        if (n < 3 || seedIndexes.isEmpty()) {
            return new BetweennessValue(centrality, Math.min(samples, n));
        }
        List<Integer> sources = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sources.add(i);
        }
        Collections.shuffle(sources, new Random(SAMPLE_SEED));
        sources = sources.subList(0, Math.min(samples, n));

        int[] distance = new int[n];
        int[] queue = new int[n];
        int[] stack = new int[n];
        double[] sigma = new double[n];
        double[] dependency = new double[n];

        for (int source : sources) {
            Arrays.fill(distance, -1);
            Arrays.fill(sigma, 0);
            Arrays.fill(dependency, 0);
            int queueHead = 0;
            int queueTail = 0;
            int stackSize = 0;
            distance[source] = 0;
            sigma[source] = 1;
            queue[queueTail++] = source;

            while (queueHead < queueTail) {
                int vertex = queue[queueHead++];
                stack[stackSize++] = vertex;
                for (int neighbor : adjacency[vertex]) {
                    if (distance[neighbor] < 0) {
                        distance[neighbor] = distance[vertex] + 1;
                        queue[queueTail++] = neighbor;
                    }
                    if (distance[neighbor] == distance[vertex] + 1) {
                        sigma[neighbor] += sigma[vertex];
                    }
                }
            }

            while (stackSize > 0) {
                int vertex = stack[--stackSize];
                if (sigma[vertex] != 0) {
                    for (int predecessor : adjacency[vertex]) {
                        if (distance[predecessor] == distance[vertex] - 1) {
                            dependency[predecessor] += (sigma[predecessor] / sigma[vertex])
                                    * (1.0 + dependency[vertex]);
                        }
                    }
                }
                if (vertex != source && seedIndexes.contains(vertex)) {
                    centrality[vertex] += dependency[vertex];
                }
            }
        }

        double normalization = (double) n / sources.size()
                / ((double) (n - 1) * (n - 2));
        for (int seed : seedIndexes) {
            centrality[seed] *= normalization;
        }
        return new BetweennessValue(centrality, sources.size());
    }

    private static EigenvectorValue eigenvector(int[][] adjacency) {
        int n = adjacency.length;
        if (n == 0) {
            return new EigenvectorValue(new double[0], 0, true);
        }
        double[] values = new double[n];
        Arrays.fill(values, 1.0 / Math.sqrt(n));
        double[] next = new double[n];
        boolean converged = false;
        int iterations = 0;
        for (; iterations < EIGENVECTOR_MAX_ITERATIONS; iterations++) {
            Arrays.fill(next, 0);
            double normSquared = 0;
            for (int i = 0; i < n; i++) {
                // Сдвиг A + I сохраняет собственные векторы A и устраняет
                // двухтактное колебание степенного метода на двудольных графах.
                double sum = values[i];
                for (int neighbor : adjacency[i]) {
                    sum += values[neighbor];
                }
                next[i] = sum;
                normSquared += sum * sum;
            }
            double norm = Math.sqrt(normSquared);
            if (norm == 0) {
                return new EigenvectorValue(new double[n], iterations + 1, true);
            }
            double difference = 0;
            for (int i = 0; i < n; i++) {
                next[i] /= norm;
                difference += Math.abs(next[i] - values[i]);
            }
            double[] swap = values;
            values = next;
            next = swap;
            if (difference < EIGENVECTOR_TOLERANCE * n) {
                converged = true;
                iterations++;
                break;
            }
        }
        double maximum = Arrays.stream(values).max().orElse(0);
        if (maximum > 0) {
            for (int i = 0; i < values.length; i++) {
                values[i] /= maximum;
            }
        }
        return new EigenvectorValue(values, iterations, converged);
    }

    private static int[] distances(int source, int[][] adjacency) {
        int[] result = new int[adjacency.length];
        Arrays.fill(result, -1);
        int[] queue = new int[adjacency.length];
        int head = 0;
        int tail = 0;
        result[source] = 0;
        queue[tail++] = source;
        while (head < tail) {
            int vertex = queue[head++];
            for (int neighbor : adjacency[vertex]) {
                if (result[neighbor] < 0) {
                    result[neighbor] = result[vertex] + 1;
                    queue[tail++] = neighbor;
                }
            }
        }
        return result;
    }

    private static ComponentStats componentStats(int[][] adjacency) {
        boolean[] visited = new boolean[adjacency.length];
        int count = 0;
        int largest = 0;
        for (int start = 0; start < adjacency.length; start++) {
            if (visited[start]) {
                continue;
            }
            count++;
            int size = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int vertex = queue.removeFirst();
                size++;
                for (int neighbor : adjacency[vertex]) {
                    if (!visited[neighbor]) {
                        visited[neighbor] = true;
                        queue.addLast(neighbor);
                    }
                }
            }
            largest = Math.max(largest, size);
        }
        return new ComponentStats(count, largest);
    }

    private record ClosenessValue(double value, int reachableNodes) {
    }

    private record BetweennessValue(double[] values, int samples) {
    }

    private record EigenvectorValue(double[] values, int iterations, boolean converged) {
    }

    private record ComponentStats(int count, int largest) {
    }
}
