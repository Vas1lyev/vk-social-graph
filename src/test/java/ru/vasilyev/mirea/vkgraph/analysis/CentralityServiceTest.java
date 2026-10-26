package ru.vasilyev.mirea.vkgraph.analysis;

import org.junit.jupiter.api.Test;
import ru.vasilyev.mirea.vkgraph.domain.Edge;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CentralityServiceTest {
    @Test
    void identifiesMiddleOfPathAsMostCentral() {
        CentralityResult result = new CentralityService().analyze(
                Set.of(1L, 2L, 3L), Set.of(new Edge(1, 2), new Edge(2, 3)),
                List.of(1L, 2L, 3L), 3);

        CentralityScore first = score(result, 1);
        CentralityScore middle = score(result, 2);
        CentralityScore last = score(result, 3);

        assertThat(middle.betweennessApprox()).isGreaterThan(first.betweennessApprox());
        assertThat(middle.betweennessApprox()).isGreaterThan(last.betweennessApprox());
        assertThat(middle.closeness()).isGreaterThan(first.closeness());
        assertThat(middle.eigenvector()).isGreaterThan(first.eigenvector());
        assertThat(result.componentCount()).isEqualTo(1);
        assertThat(result.largestComponentSize()).isEqualTo(3);
    }

    private static CentralityScore score(CentralityResult result, long id) {
        return result.scores().stream().filter(value -> value.userId() == id).findFirst().orElseThrow();
    }
}
