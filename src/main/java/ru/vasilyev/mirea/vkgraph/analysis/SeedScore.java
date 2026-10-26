package ru.vasilyev.mirea.vkgraph.analysis;

public record SeedScore(long userId, int sharedUnique, int sharedSum, int seedDegree, boolean dataComplete) {
}
