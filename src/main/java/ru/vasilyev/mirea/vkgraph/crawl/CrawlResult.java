package ru.vasilyev.mirea.vkgraph.crawl;

import ru.vasilyev.mirea.vkgraph.domain.Edge;
import ru.vasilyev.mirea.vkgraph.domain.UserProfile;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record CrawlResult(
        Map<Long, UserProfile> profiles,
        Set<Edge> edges,
        List<Long> seedIds,
        Map<Long, Set<Long>> seedFriends,
        Map<Long, String> fetchStatuses,
        List<String> unresolvedInputs,
        boolean complete
) {
}
