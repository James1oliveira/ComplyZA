package za.co.complyza.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits the text of a South African Act into chunks that follow its legal structure
 * (Chapter -> Section -> Subsection) instead of fixed token windows.
 *
 * Handles the two heading styles found in SA Acts:
 * <pre>
 *   Security measures on integrity and confidentiality      19. Definitions
 *   19. (1) A responsible party must ...                   (1) In this Act ...
 * </pre>
 * and the "Arrangement of sections" table of contents at the start of an Act
 * (numbering restarts at 1 when the real body begins, so the TOC is discarded).
 *
 * Plain Java with no Spring dependency, so it is easy to unit test.
 */
public class LegalSectionParser {

    private static final Pattern CHAPTER = Pattern.compile("^CHAPTER\\s+(\\d{1,2}[A-Z]?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_START = Pattern.compile("^(\\d{1,3})([A-Z]?)\\.\\s+(.*)$");
    private static final Pattern PAGE_NUMBER = Pattern.compile("^\\d{1,4}$");
    private static final Pattern GAZETTE_NOISE = Pattern.compile(
            "(?i)^(.*GOVERNMENT GAZETTE.*|.*STAATSKOERANT.*|No\\.\\s*\\d+\\s*$|Act No\\.\\s*\\d+ of \\d{4}\\s*$)");
    /**
     * Running page header and footer in Laws.Africa / LawLibrary PDFs, e.g.
     * "Protection of Personal Information Act, 2013 (Act 4 of 2013) South Africa" and
     * "By LawLibrary, AfricanLII, ... Share widely and freely. 58".
     */
    private static final Pattern PAGE_FURNITURE = Pattern.compile(
            "(?i)^([A-Z][^.;:]*Act, \\d{4} \\(Act \\d+ of \\d{4}\\)( South Africa)?|South Africa|By LawLibrary\\b.*)$");
    /** The Schedule after the last section (amendments to other laws) is not part of any section. */
    private static final Pattern SCHEDULE = Pattern.compile("(?i)^Schedules?$");
    /** "(1)" at line start, or after end-of-sentence punctuation. */
    private static final Pattern SUBSECTION = Pattern.compile("(?:^|(?<=[.;:]\\s))\\((\\d{1,2}[A-Z]?)\\)\\s", Pattern.MULTILINE);

    private static final int MAX_JUMP = 15;
    private static final int MIN_BODY_CHARS = 40;

    private final int maxChunkChars;

    public LegalSectionParser() {
        this(2500);
    }

    public LegalSectionParser(int maxChunkChars) {
        this.maxChunkChars = maxChunkChars;
    }
    public List<SectionChunk> parse(String rawText, String act) {
        List<String> lines = cleanLines(rawText);

        List<RawSection> sections = new ArrayList<>();
        RawSection current = null;
        String chapter = "";
        String chapterTitle = "";
        boolean expectChapterTitle = false;
        int lastNumber = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (current != null && SCHEDULE.matcher(line).matches()) {
                break;
            }

            Matcher ch = CHAPTER.matcher(line);
            if (ch.matches()) {
                chapter = ch.group(1);
                chapterTitle = "";
                expectChapterTitle = true;
                continue;
            }
            if (expectChapterTitle) {
                chapterTitle = line;
                expectChapterTitle = false;
                continue;
            }

            Matcher sm = SECTION_START.matcher(line);
            if (sm.matches()) {
                int number = Integer.parseInt(sm.group(1));
                String suffix = sm.group(2);
                String rest = sm.group(3).trim();

                boolean restart = number == 1 && suffix.isEmpty() && lastNumber > 1;
                boolean plausibleNext = number > lastNumber && number <= lastNumber + MAX_JUMP;
                boolean lettered = number == lastNumber && !suffix.isEmpty();

                if (restart || plausibleNext || lettered || lastNumber == 0) {
                    if (restart) {
                        // Everything so far was the table of contents.
                        sections.clear();
                    }
                    String heading = "";
                    String firstBody = rest;

                    // Style A: heading on the previous line, body starts "(1) ..."
                    if (current != null && !current.body.isEmpty()) {
                        String prev = current.body.get(current.body.size() - 1);
                        if (looksLikeHeading(prev)) {
                            heading = prev;
                            current.body.remove(current.body.size() - 1);
                        }
                    }
                    // Style B: "19. Heading" with the body on following lines
                    if (heading.isEmpty() && looksLikeHeading(rest)) {
                        heading = rest;
                        firstBody = "";
                    }

                    String key = number + suffix;
                    current = new RawSection(chapter, chapterTitle, key, heading);
                    if (!firstBody.isEmpty()) {
                        current.body.add(firstBody);
                    }
                    sections.add(current);
                    lastNumber = number;
                    continue;
                }
            }

            if (current != null) {
                current.body.add(line);
            }
        }
        List<SectionChunk> chunks = new ArrayList<>();
        // If a section number appears twice, keep the longer version
        Map<String, RawSection> best = new LinkedHashMap<>();
        for (RawSection s : sections) {
            RawSection prev = best.get(s.number);
            if (prev == null || s.length() > prev.length()) {
                best.put(s.number, s);
            }
        }
        for (RawSection s : best.values()) {
            String body = String.join("\n", s.body).trim();
            if (body.length() < MIN_BODY_CHARS) {
                continue; // TOC remnant or empty heading
            }
            chunks.addAll(split(act, s, body));
        }
        return chunks;
    }

    private List<SectionChunk> split(String act, RawSection s, String body) {
        if (body.length() <= maxChunkChars) {
            return List.of(chunk(act, s, "", body));
        }
        // Split at subsection markers, then pack consecutive subsections up to maxChunkChars.
        List<int[]> starts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        Matcher m = SUBSECTION.matcher(body);
        while (m.find()) {
            starts.add(new int[]{m.start()});
            labels.add("(" + m.group(1) + ")");
        }
        if (starts.size() < 2) {
            return hardSplit(act, s, body);
        }

        List<SectionChunk> out = new ArrayList<>();
        // Text before the first "(1)" belongs with the first subsection
        int firstStart = 0;
        StringBuilder buf = new StringBuilder();
        String firstLabel = null;
        String lastLabel = null;
        for (int i = 0; i < starts.size(); i++) {
            int from = i == 0 ? firstStart : starts.get(i)[0];
            int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : body.length();
            String piece = body.substring(from, to);
            if (buf.length() > 0 && buf.length() + piece.length() > maxChunkChars) {
                out.add(chunk(act, s, range(firstLabel, lastLabel), buf.toString().trim()));
                buf.setLength(0);
                firstLabel = null;
            }
            if (firstLabel == null) {
                firstLabel = labels.get(i);
            }
            lastLabel = labels.get(i);
            buf.append(piece);
        }
        if (buf.length() > 0) {
            out.add(chunk(act, s, range(firstLabel, lastLabel), buf.toString().trim()));
        }
        return out;
    }

    private List<SectionChunk> hardSplit(String act, RawSection s, String body) {
        List<SectionChunk> out = new ArrayList<>();
        int part = 1;
        for (int i = 0; i < body.length(); i += maxChunkChars) {
            String piece = body.substring(i, Math.min(body.length(), i + maxChunkChars));
            out.add(chunk(act, s, "part " + part++, piece.trim()));
        }
        return out;
    }

    private SectionChunk chunk(String act, RawSection s, String subsections, String body) {
        String header = act + " section " + s.number
                + (s.heading.isEmpty() ? "" : ": " + s.heading)
                + (subsections.isEmpty() ? "" : " " + subsections);
        return new SectionChunk(act, s.chapter, s.chapterTitle, s.number, s.heading, subsections,
                header + "\n" + body);
    }

    private static String range(String first, String last) {
        return first.equals(last) ? first : first + "-" + last;
    }

    /** A heading is a short line that starts with a capital and does not end like a sentence. */
    static boolean looksLikeHeading(String line) {
        if (line == null || line.isBlank() || line.length() > 160) {
            return false;
        }
        char first = line.charAt(0);
        char last = line.charAt(line.length() - 1);
        return Character.isUpperCase(first)
                && last != '.' && last != ';' && last != ':' && last != ','
                && last != '-' && last != '–' && last != '—'
                && last != '-' && last != '\u2013' && last != '\u2014'
                && !line.startsWith("(");
    }

    private static List<String> cleanLines(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.replace("\r", "").split("\n")) {
            String t = line.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
            if (t.isEmpty() || PAGE_NUMBER.matcher(t).matches() || GAZETTE_NOISE.matcher(t).matches()
                    || PAGE_FURNITURE.matcher(t).matches()) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private static final class RawSection {
        final String chapter;
        final String chapterTitle;
        final String number;
        final String heading;
        final List<String> body = new ArrayList<>();

        RawSection(String chapter, String chapterTitle, String number, String heading) {
            this.chapter = chapter;
            this.chapterTitle = chapterTitle;
            this.number = number;
            this.heading = heading;
        }

        int length() {
            return body.stream().mapToInt(String::length).sum();
        }
    }
}
