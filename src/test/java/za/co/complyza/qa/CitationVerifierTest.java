package za.co.complyza.qa;

import org.junit.jupiter.api.Test;
import za.co.complyza.search.RetrievedSection;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CitationVerifierTest {

    private final CitationVerifier verifier = new CitationVerifier();

    private final RetrievedSection s3 = new RetrievedSection("id-3", "SAMPLE", "2", "3", "Security safeguards", "",
            "SAMPLE section 3: Security safeguards\n(1) A responsible party must secure the integrity and "
                    + "confidentiality of personal\ninformation in its possession by taking appropriate measures.",
            "https://example.org/act", "Act 1 of 2024", 0.9);

    private final Map<String, RetrievedSection> sources = Map.of("S1", s3);

    @Test
    void keepsVerbatimQuoteAcrossLineBreaks() {
        var outcome = verifier.verify(List.of(new LlmAnswer.LlmCitation("S1",
                "must secure the integrity and confidentiality of personal information")), sources);

        assertThat(outcome.rejected()).isZero();
        assertThat(outcome.verified()).singleElement().satisfies(c -> {
            assertThat(c.section()).isEqualTo("3");
            assertThat(c.sourceUrl()).isEqualTo("https://example.org/act");
        });
    }

    @Test
    void rejectsInventedQuote() {
        var outcome = verifier.verify(List.of(new LlmAnswer.LlmCitation("S1",
                "must notify the Regulator within 72 hours")), sources);

        assertThat(outcome.verified()).isEmpty();
        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownSourceId() {
        var outcome = verifier.verify(List.of(new LlmAnswer.LlmCitation("S9",
                "must secure the integrity and confidentiality")), sources);
        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void allowsEllipsisWhenPiecesAppearInOrder() {
        assertThat(CitationVerifier.quoteAppearsIn(
                "A responsible party must secure ... by taking appropriate measures", s3.text())).isTrue();
        assertThat(CitationVerifier.quoteAppearsIn(
                "by taking appropriate measures ... A responsible party must secure", s3.text())).isFalse();
    }

    @Test
    void rejectsTrivialQuotes() {
        assertThat(CitationVerifier.quoteAppearsIn("the", s3.text())).isFalse();
    }
}
