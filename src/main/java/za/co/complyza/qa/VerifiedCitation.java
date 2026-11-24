package za.co.complyza.qa;

/** A citation that passed the deterministic check: the section was retrieved and the quote is verbatim. */
public record VerifiedCitation(
        String sourceId,
        String act,
        String section,
        String heading,
        String quote,
        String sourceUrl,
        String versionLabel) {
}
