package ru.vasilyev.mirea.vkgraph.analysis;

import ru.vasilyev.mirea.vkgraph.crawl.CrawlResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AnalysisService {
    public AnalysisResult analyze(CrawlResult crawl) {
        List<Long> seeds = crawl.seedIds();
        Map<Long, Set<Long>> friends = crawl.seedFriends();
        Map<Long, Integer> frequency = new HashMap<>();
        friends.values().forEach(ids -> ids.forEach(id -> frequency.merge(id, 1, Integer::sum)));

        Map<Long, Integer> sharedSums = new HashMap<>();
        List<PairScore> pairs = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            for (int j = i + 1; j < seeds.size(); j++) {
                long first = seeds.get(i);
                long second = seeds.get(j);
                Set<Long> firstFriends = friends.get(first);
                Set<Long> secondFriends = friends.get(second);
                if (firstFriends == null || secondFriends == null) {
                    continue;
                }
                int mutual = intersectionSize(firstFriends, secondFriends);
                pairs.add(new PairScore(first, second, mutual));
                sharedSums.merge(first, mutual, Integer::sum);
                sharedSums.merge(second, mutual, Integer::sum);
            }
        }

        List<SeedScore> scores = new ArrayList<>();
        Set<Long> seedSet = Set.copyOf(seeds);
        for (long seed : seeds) {
            Set<Long> ownFriends = friends.get(seed);
            boolean dataComplete = ownFriends != null && "SUCCESS".equals(crawl.fetchStatuses().get(seed));
            int sharedUnique = ownFriends == null ? 0 : (int) ownFriends.stream()
                    .filter(id -> frequency.getOrDefault(id, 0) > 1)
                    .count();
            int seedDegree = ownFriends == null ? 0 : (int) ownFriends.stream().filter(seedSet::contains).count();
            scores.add(new SeedScore(seed, sharedUnique, sharedSums.getOrDefault(seed, 0), seedDegree, dataComplete));
        }

        scores.sort(Comparator.comparingInt(SeedScore::sharedUnique).reversed()
                .thenComparing(Comparator.comparingInt(SeedScore::sharedSum).reversed())
                .thenComparingLong(SeedScore::userId));
        pairs.sort(Comparator.comparingInt(PairScore::mutualCount).reversed()
                .thenComparingLong(PairScore::firstUserId)
                .thenComparingLong(PairScore::secondUserId));
        return new AnalysisResult(List.copyOf(scores), List.copyOf(pairs));
    }

    static int intersectionSize(Set<Long> first, Set<Long> second) {
        Set<Long> smaller = first.size() <= second.size() ? first : second;
        Set<Long> larger = smaller == first ? second : first;
        int count = 0;
        for (long id : smaller) {
            if (larger.contains(id)) {
                count++;
            }
        }
        return count;
    }
}
