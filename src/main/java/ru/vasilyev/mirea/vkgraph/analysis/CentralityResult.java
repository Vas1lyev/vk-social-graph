package ru.vasilyev.mirea.vkgraph.analysis;

import java.util.List;

public record CentralityResult(
        List<CentralityScore> scores,
        int nodeCount,
        int edgeCount,
        int componentCount,
        int largestComponentSize,
        int betweennessSamples,
        int eigenvectorIterations,
        boolean eigenvectorConverged
) {
}
