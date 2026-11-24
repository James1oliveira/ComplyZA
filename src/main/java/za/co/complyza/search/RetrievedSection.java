package za.co.complyza.search;

public record RetrievedSection(
        String id,
        String act,
        String chapter,
        String section,
        String heading,
        String subsections,
        String text,
        String sourceUrl,
        String versionLabel,
        double score) {

    public RetrievedSection withScore(double newScore) {
        return new RetrievedSection(id, act, chapter, section, heading, subsections, text, sourceUrl, versionLabel, newScore);
    }

    /** e.g. "POPIA s19" or "POPIA s19 (1)-(3)" */
    public String label() {
        return act + " s" + section + (subsections == null || subsections.isBlank() ? "" : " " + subsections);
    }
}
