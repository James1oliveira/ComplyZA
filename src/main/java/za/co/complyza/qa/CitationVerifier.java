package za.co.complyza.qa;

import za.co.complyza.search.RetrievedSection;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic guard against invented law. A citation is kept only if:
 *  1. its sourceId refers to a section we actually retrieved, and
 *  2. its quote appears verbatim in that section (ignoring whitespace, case, smart quotes, dashes;
 *     "..." may be used to skip text, but the pieces must appear in order).
 *
 * Act, section number and URL always come from the retrieved source, never from the model.
 */
public class CitationVerifier {

    private static final int MIN_QUOTE_CHARS = 12;

    public record Outcome(List<VerifiedCitation> verified, int rejected) {
    }

    public Outcome verify(List<LlmAnswer.LlmCitation> citations, Map<String, RetrievedSection> sourcesById) {
        List<VerifiedCitation> ok = new ArrayList<>();
        int rejected = 0;
        if (citations == null) {
            return new Outcome(ok, 0);
        }
        for (LlmAnswer.LlmCitation c : citations) {
            if (c == null || c.sourceId() == null || c.quote() == null) {
                rejected++;
                continue;
            }
            RetrievedSection src = sourcesById.get(c.sourceId().trim().toUpperCase());
            if (src == null || !quoteAppearsIn(c.quote(), src.text())) {
                rejected++;
                continue;
            }
            boolean duplicate = ok.stream().anyMatch(v -> v.sourceId().equals(c.sourceId().trim().toUpperCase())
                    && normalize(v.quote()).equals(normalize(c.quote())));
            if (!duplicate) {
                ok.add(new VerifiedCitation(c.sourceId().trim().toUpperCase(), src.act(), src.section(), src.heading(),
                        c.quote().trim(), src.sourceUrl(), src.versionLabel()));
            }
        }
        return new Outcome(ok, rejected);
    }

    static boolean quoteAppearsIn(String quote, String sourceText) {
        String haystack = normalize(sourceText);
        String[] parts = normalize(quote).split("\\s*(?:\\.\\.\\.|…)\\s*");
        int from = 0;
        int matchedChars = 0;
        for (String part : parts) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            int idx = haystack.indexOf(p, from);
            if (idx < 0) {
                return false;
            }
            from = idx + p.length();
            matchedChars += p.length();
        }
        return matchedChars >= MIN_QUOTE_CHARS;
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replaceAll("[\"]", "")
                .toLowerCase();
        // PDF extraction hyphenates words across lines: "informa- tion" -> "information"
        n = n.replaceAll("(\\p{L})-\\s+(\\p{L})", "$1$2");
        return n.replaceAll("\\s+", " ").trim();
    }
}
