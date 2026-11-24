package za.co.complyza.qa;

import java.util.List;
import java.util.Map;

public record ComplianceResponse(
        String question,
        String answer,
        Confidence confidence,
        List<VerifiedCitation> citations,
        int rejectedCitations,
        List<SourceRef> sourcesConsulted,
        List<String> asAt,
        String disclaimer,
        Map<String, Integer> redactions) {

    public record SourceRef(String sourceId, String label, String heading, String sourceUrl) {
    }
}
