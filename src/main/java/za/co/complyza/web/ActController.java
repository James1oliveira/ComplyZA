package za.co.complyza.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only endpoints for the Acts browser: which Acts are loaded, and the full text of one Act
 * grouped by section. Open to analysts (anything under /api needs the ANALYST or ADMIN role).
 *
 * <p>The full text of an Act only changes when it is re-ingested, so each response is tagged with the
 * ingestion id (ETag): browsers revalidate and get a 304 with no body, and the server builds each Act's
 * text once per ingestion and keeps it in memory.
 */
@RestController
@RequestMapping("/api/acts")
public class ActController {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+)(.*)$");
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");

    private static final String[] FICA_INFO = {
            "Financial Intelligence Centre Act 38 of 2001",
            "South Africa's anti-money-laundering law. It establishes the Financial Intelligence Centre and "
                    + "sets duties for accountable institutions, such as knowing their customers, keeping "
                    + "records and reporting suspicious and unusual transactions."};

    private static final Map<String, String[]> INFO = Map.of(
            "POPIA", new String[]{
                    "Protection of Personal Information Act 4 of 2013",
                    "South Africa's data protection law. It gives effect to the constitutional right to privacy by "
                            + "setting conditions for how a responsible party may process personal information, "
                            + "what rights data subjects have, how personal information may be sent to other "
                            + "countries, and how the Information Regulator enforces the Act."},
            "FICA", FICA_INFO,
            "FIC", FICA_INFO);

    /** Built Act text, keyed by Act; reused until that Act is re-ingested. */
    private record CachedAct(String ingestionId, Map<String, Object> body) {
    }

    private final Map<String, CachedAct> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public ActController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One entry per loaded Act (latest version), in a single query. */
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.act, s.version_label, s.chunk_count, s.ingested_at, COALESCE(v.sections, 0) AS sections
                FROM (SELECT DISTINCT ON (act) act, version_label, chunk_count, ingested_at
                        FROM source_document ORDER BY act, id DESC) s
                LEFT JOIN (SELECT metadata ->> 'act' AS act, count(DISTINCT metadata ->> 'section') AS sections
                             FROM vector_store GROUP BY 1) v ON v.act = s.act
                ORDER BY s.act
                """);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String act = String.valueOf(r.get("act"));
            String[] info = info(act, "Regulation text loaded into Comply ZA. Open it to read every section.");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("act", act);
            m.put("name", info[0]);
            m.put("summary", info[1]);
            m.put("versionLabel", r.get("version_label"));
            m.put("sections", r.get("sections"));
            m.put("chunks", r.get("chunk_count"));
            m.put("ingestedAt", r.get("ingested_at"));
            out.add(m);
        }
        return out;
    }

    /** Full text of one Act, grouped by section and sorted by section number. */
    @GetMapping("/{act}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String act, WebRequest request) {
        String key = act.trim().toUpperCase();
        List<Map<String, Object>> latest = jdbc.queryForList(
                "SELECT ingestion_id, version_label FROM source_document WHERE act = ? ORDER BY id DESC LIMIT 1", key);
        if (latest.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String ingestionId = String.valueOf(latest.get(0).get("ingestion_id"));
        String etag = "\"" + key + "-" + ingestionId + "\"";
        if (request.checkNotModified(etag)) {
            return null; // Spring has already sent 304 Not Modified
        }

        CachedAct cached = cache.get(key);
        if (cached == null || !cached.ingestionId().equals(ingestionId)) {
            Map<String, Object> body = build(key, Objects.toString(latest.get(0).get("version_label"), ""));
            if (body == null) {
                return ResponseEntity.notFound().build();
            }
            cached = new CachedAct(ingestionId, body);
            cache.put(key, cached);
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(cached.body());
    }

    private Map<String, Object> build(String key, String versionLabel) {
        // Pull the fields straight out of the JSON in SQL: no per-row JSON parsing in Java.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT content,
                       metadata ->> 'section'      AS section,
                       metadata ->> 'heading'      AS heading,
                       metadata ->> 'chapter'      AS chapter,
                       metadata ->> 'chapterTitle' AS chapter_title,
                       metadata ->> 'subsections'  AS subsections
                FROM vector_store
                WHERE metadata ->> 'act' = ?
                """, key);
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        Map<String, List<String[]>> pieces = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String section = str(row, "section");
            if (section.isBlank()) {
                continue;
            }
            // The first line of every chunk is a "POPIA section 19: Heading" label; the heading is sent separately.
            String content = str(row, "content");
            int newline = content.indexOf('\n');
            String body = (newline >= 0 ? content.substring(newline + 1) : content).trim();

            Map<String, Object> s = sections.computeIfAbsent(section, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("section", k);
                m.put("heading", "");
                m.put("chapter", "");
                m.put("chapterTitle", "");
                return m;
            });
            fillIfBlank(s, "heading", str(row, "heading"));
            fillIfBlank(s, "chapter", str(row, "chapter"));
            fillIfBlank(s, "chapterTitle", str(row, "chapter_title"));
            pieces.computeIfAbsent(section, k -> new ArrayList<>()).add(new String[]{str(row, "subsections"), body});
        }

        // Pieces of one section: "(1)-(3)" before "(10)", and "part 2" before "part 10".
        Comparator<String[]> pieceOrder = Comparator.<String[]>comparingInt(p -> firstNumber(p[0]))
                .thenComparing(p -> p[0]);
        List<Map<String, Object>> ordered = new ArrayList<>(sections.size());
        for (Map.Entry<String, Map<String, Object>> e : sections.entrySet()) {
            List<String[]> parts = pieces.get(e.getKey());
            parts.sort(pieceOrder);
            StringBuilder text = new StringBuilder();
            for (String[] p : parts) {
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(p[1]);
            }
            e.getValue().put("text", text.toString());
            ordered.add(e.getValue());
        }
        ordered.sort(Comparator.<Map<String, Object>>comparingInt(m -> number(str(m, "section")))
                .thenComparing(m -> suffix(str(m, "section"))));

        String[] info = info(key, "Regulation text loaded into Comply ZA. Every section is listed below.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("act", key);
        body.put("name", info[0]);
        body.put("summary", info[1]);
        body.put("versionLabel", versionLabel);
        body.put("sectionCount", ordered.size());
        body.put("sections", ordered);
        return body;
    }

    private static String[] info(String act, String fallbackSummary) {
        return INFO.getOrDefault(act, new String[]{act, fallbackSummary});
    }

    private static void fillIfBlank(Map<String, Object> target, String key, String value) {
        if (!value.isBlank() && String.valueOf(target.get(key)).isBlank()) {
            target.put(key, value);
        }
    }

    private static String str(Map<String, Object> m, String key) {
        return Objects.toString(m.get(key), "");
    }

    /** Leading number of a section label such as "72" or "19A"; labels without one sort last. */
    private static int number(String section) {
        Matcher m = LEADING_NUMBER.matcher(section);
        return m.find() ? safeInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static String suffix(String section) {
        Matcher m = LEADING_NUMBER.matcher(section);
        return m.find() ? m.group(2) : section;
    }

    /** First number anywhere in a subsection label: "(10)-(12)" gives 10, "part 2" gives 2, "" gives 0. */
    private static int firstNumber(String label) {
        Matcher m = FIRST_NUMBER.matcher(label);
        return m.find() ? safeInt(m.group(1)) : 0;
    }

    private static int safeInt(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
