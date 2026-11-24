package za.co.complyza.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.complyza.config.AppProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hybrid retrieval:
 *  1. exact section references in the question ("section 72", "s 19(1)")
 *  2. pgvector semantic similarity (meaning)
 *  3. PostgreSQL full-text search (exact legal terms like "accountable institution")
 * merged with Reciprocal Rank Fusion.
 */
@Service
public class HybridSearchService {

    private static final Pattern SECTION_REF = Pattern.compile("(?i)\\b(?:section|sec\\.?|s\\.?)\\s*(\\d{1,3}[A-Z]?)\\b");
    private static final double EXACT_WEIGHT = 2.0;
    private static final double VECTOR_WEIGHT = 1.0;
    private static final double KEYWORD_WEIGHT = 1.0;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AppProperties.Retrieval cfg;

    public HybridSearchService(VectorStore vectorStore, JdbcTemplate jdbc, ObjectMapper objectMapper, AppProperties props) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.cfg = props.retrieval();
    }

    public List<RetrievedSection> search(String query) {
        List<RetrievedSection> exact = exactSectionMatches(query);
        List<RetrievedSection> vector = vectorSearch(query);
        List<RetrievedSection> keyword = keywordSearch(query);
        return RrfMerger.merge(
                List.of(exact, vector, keyword),
                new double[]{EXACT_WEIGHT, VECTOR_WEIGHT, KEYWORD_WEIGHT},
                cfg.finalTopK());
    }

    List<RetrievedSection> vectorSearch(String query) {
        List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(cfg.vectorTopK())
                .similarityThreshold(cfg.similarityThreshold())
                .build());
        if (docs == null) {
            return List.of();
        }
        return docs.stream()
                .map(d -> fromMetadata(d.getId(), d.getText(), d.getMetadata(), d.getScore() == null ? 0 : d.getScore()))
                .toList();
    }

    List<RetrievedSection> keywordSearch(String query) {
        String tsQuery = toOrTsQuery(query);
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id::text AS id, content, metadata::text AS metadata, ts_rank_cd(tsv, q) AS rank
                FROM vector_store, to_tsquery('english', ?) q
                WHERE tsv @@ q
                ORDER BY rank DESC
                LIMIT ?
                """,
                (rs, i) -> fromMetadata(rs.getString("id"), rs.getString("content"),
                        parse(rs.getString("metadata")), rs.getDouble("rank")),
                tsQuery, cfg.keywordTopK());
    }

    List<RetrievedSection> exactSectionMatches(String query) {
        Matcher m = SECTION_REF.matcher(query);
        List<String> refs = new java.util.ArrayList<>();
        while (m.find()) {
            refs.add(m.group(1).toUpperCase());
        }
        if (refs.isEmpty()) {
            return List.of();
        }
        return refs.stream().distinct().limit(3)
                .flatMap(ref -> jdbc.query("""
                        SELECT id::text AS id, content, metadata::text AS metadata
                        FROM vector_store
                        WHERE metadata ->> 'section' = ?
                        LIMIT 5
                        """,
                        (rs, i) -> fromMetadata(rs.getString("id"), rs.getString("content"),
                                parse(rs.getString("metadata")), 1.0),
                        ref).stream())
                .toList();
    }

    /** "When may info be transferred?" -> "may | info | transferred" (alphanumeric only, so injection-safe). */
    static String toOrTsQuery(String query) {
        return Arrays.stream(query.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() >= 3)
                .distinct()
                .limit(12)
                .collect(Collectors.joining(" | "));
    }

    private Map<String, Object> parse(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static RetrievedSection fromMetadata(String id, String text, Map<String, Object> md, double score) {
        return new RetrievedSection(
                id,
                str(md, "act"),
                str(md, "chapter"),
                str(md, "section"),
                str(md, "heading"),
                str(md, "subsections"),
                text,
                str(md, "sourceUrl"),
                str(md, "versionLabel"),
                score);
    }

    private static String str(Map<String, Object> md, String key) {
        return Objects.toString(md.get(key), "");
    }
}
