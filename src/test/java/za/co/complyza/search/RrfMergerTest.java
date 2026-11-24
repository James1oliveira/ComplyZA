package za.co.complyza.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfMergerTest {

    private static RetrievedSection s(String id) {
        return new RetrievedSection(id, "A", "", id, "", "", "text " + id, "", "", 0);
    }

    @Test
    void documentsFoundByBothRetrieversRankFirst() {
        var vector = List.of(s("a"), s("b"), s("c"));
        var keyword = List.of(s("c"), s("d"));

        var merged = RrfMerger.merge(List.of(vector, keyword), new double[]{1, 1}, 10);

        assertThat(merged.get(0).id()).isEqualTo("c");
        assertThat(merged).extracting(RetrievedSection::id).containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    @Test
    void weightedExactMatchWins() {
        var exact = List.of(s("x"));
        var vector = List.of(s("a"), s("x"));

        var merged = RrfMerger.merge(List.of(exact, vector), new double[]{2, 1}, 10);

        assertThat(merged.get(0).id()).isEqualTo("x");
    }

    @Test
    void respectsLimit() {
        var merged = RrfMerger.merge(List.of(List.of(s("a"), s("b"), s("c"))), new double[]{1}, 2);
        assertThat(merged).hasSize(2);
    }

    @Test
    void buildsInjectionSafeOrQuery() {
        assertThat(HybridSearchService.toOrTsQuery("When may info be sent abroad?'; DROP TABLE x;--"))
                .isEqualTo("when | may | info | sent | abroad | drop | table");
    }
}
