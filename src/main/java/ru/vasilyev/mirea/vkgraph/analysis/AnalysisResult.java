package ru.vasilyev.mirea.vkgraph.analysis;

import java.util.List;

public record AnalysisResult(List<SeedScore> seedScores, List<PairScore> pairScores) {
}
