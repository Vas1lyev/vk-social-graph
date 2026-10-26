package ru.vasilyev.mirea.vkgraph.analysis;

import org.junit.jupiter.api.Test;
import ru.vasilyev.mirea.vkgraph.crawl.CrawlResult;
import ru.vasilyev.mirea.vkgraph.domain.UserProfile;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisServiceTest {
    @Test
    void calculatesPairAndPerSeedScores() {
        CrawlResult crawl = new CrawlResult(
                Map.of(
                        1L, new UserProfile(1, "a", "A", true, 0),
                        2L, new UserProfile(2, "b", "B", true, 0),
                        3L, new UserProfile(3, "c", "C", true, 0)
                ),
                Set.of(), List.of(1L, 2L, 3L),
                Map.of(
                        1L, Set.of(10L, 11L, 12L, 2L),
                        2L, Set.of(10L, 11L, 13L, 1L),
                        3L, Set.of(10L, 14L)
                ),
                Map.of(1L, "SUCCESS", 2L, "SUCCESS", 3L, "SUCCESS"), List.of(), true);

        AnalysisResult result = new AnalysisService().analyze(crawl);

        assertThat(result.pairScores().getFirst()).isEqualTo(new PairScore(1, 2, 2));
        assertThat(result.seedScores()).extracting(SeedScore::userId).containsExactly(1L, 2L, 3L);
        assertThat(result.seedScores()).extracting(SeedScore::sharedUnique).containsExactly(2, 2, 1);
        assertThat(result.seedScores().getFirst().seedDegree()).isEqualTo(1);
    }
}
