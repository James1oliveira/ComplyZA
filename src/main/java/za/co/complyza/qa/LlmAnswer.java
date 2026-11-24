package za.co.complyza.qa;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** What the model must return (Spring AI converts the JSON into this record). */
public record LlmAnswer(
        @JsonPropertyDescription("Plain-English answer based only on the sources")
        String answer,
        @JsonPropertyDescription("One citation per claim")
        List<LlmCitation> citations,
        @JsonPropertyDescription("HIGH if the sources answer the question directly, LOW if partially, INSUFFICIENT_SOURCES if not at all")
        Confidence confidence) {

    public record LlmCitation(
            @JsonPropertyDescription("Source id exactly as given, e.g. S2")
            String sourceId,
            @JsonPropertyDescription("Short excerpt copied word-for-word from that source (8 to 40 words)")
            String quote) {
    }
}
