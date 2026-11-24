package za.co.complyza.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: combines ranked lists from different retrievers (exact section match,
 * vector similarity, full-text keyword) without having to calibrate their raw scores.
 *
 *   score(doc) = sum over lists of  weight / (K + rank)
 */
public final class RrfMerger {

    static final int K = 60;

    private RrfMerger() {
    }

    public static List<RetrievedSection> merge(List<List<RetrievedSection>> lists, double[] weights, int limit) {
        if (weights.length != lists.size()) {
            throw new IllegalArgumentException("weights must match lists");
        }
        Map<String, RetrievedSection> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int l = 0; l < lists.size(); l++) {
            List<RetrievedSection> list = lists.get(l);
            for (int rank = 0; rank < list.size(); rank++) {
                RetrievedSection s = list.get(rank);
                byId.putIfAbsent(s.id(), s);
                scores.merge(s.id(), weights[l] / (K + rank + 1), Double::sum);
            }
        }
        List<RetrievedSection> merged = new ArrayList<>();
        scores.forEach((id, score) -> merged.add(byId.get(id).withScore(score)));
        merged.sort(Comparator.comparingDouble(RetrievedSection::score).reversed());
        return merged.size() > limit ? merged.subList(0, limit) : merged;
    }
}
