package ru.vasilyev.mirea.vkgraph.domain;

public record Edge(long source, long target) implements Comparable<Edge> {
    public Edge {
        if (source == target) {
            throw new IllegalArgumentException("Петли в графе не поддерживаются");
        }
        if (source > target) {
            long tmp = source;
            source = target;
            target = tmp;
        }
    }

    @Override
    public int compareTo(Edge other) {
        int bySource = Long.compare(source, other.source);
        return bySource != 0 ? bySource : Long.compare(target, other.target);
    }
}
