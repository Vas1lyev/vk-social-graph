package ru.vasilyev.mirea.vkgraph.analysis;

public record CentralityScore(
        long userId,
        double betweennessApprox,
        double closeness,
        double eigenvector,
        int reachableNodes,
        int observedDegree
) {
}
