package za.co.complyza.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Loads an Act (PDF or plain text) into the vector store:
 * hash -> skip if already loaded -> extract text -> split by section -> embed -> replace older version.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;
    private final LegalSectionParser parser = new LegalSectionParser();

    public IngestionService(VectorStore vectorStore, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
    }

    public record IngestResult(String status, String act, String versionLabel, int chunks, int sections,
                               String ingestionId, String message) {
    }

    public IngestResult ingest(byte[] content, String filename, String act, String versionLabel, String sourceUrl) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Empty file");
        }
        String actKey = act.trim().toUpperCase();
        String hash = sha256(content);

        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM source_document WHERE content_hash = ?", Integer.class, hash);
        if (existing != null && existing > 0) {
            return new IngestResult("SKIPPED", actKey, versionLabel, 0, 0, null,
                    "This exact file was already ingested.");
        }

        String text = extractText(content, filename);
        List<SectionChunk> chunks = parser.parse(text, actKey);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("No sections found. Is this the English text of an Act?");
        }

        String ingestionId = UUID.randomUUID().toString();
        List<Document> docs = chunks.stream()
                .map(c -> new Document(c.text(), metadata(c, versionLabel, sourceUrl, ingestionId)))
                .toList();

        // Embed and store the new version first; only then remove the old one,
        // so a failed embedding call never leaves the Act missing.
        vectorStore.add(docs);
        int removed = jdbc.update("""
                DELETE FROM vector_store
                WHERE metadata ->> 'act' = ? AND metadata ->> 'ingestionId' <> ?
                """, actKey, ingestionId);

        jdbc.update("""
                INSERT INTO source_document (act, version_label, content_hash, source_url, ingestion_id, chunk_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """, actKey, versionLabel, hash, sourceUrl, ingestionId, docs.size());

        int sections = (int) chunks.stream().map(SectionChunk::section).distinct().count();
        log.info("Ingested {} ({}): {} sections, {} chunks, replaced {} old chunks",
                actKey, versionLabel, sections, docs.size(), removed);
        return new IngestResult("INGESTED", actKey, versionLabel, docs.size(), sections, ingestionId,
                removed > 0 ? "Replaced " + removed + " chunks from a previous version." : "New Act loaded.");
    }

    public List<Map<String, Object>> documents() {
        return jdbc.queryForList("""
                SELECT act, version_label, source_url, chunk_count, ingested_at
                FROM source_document ORDER BY ingested_at DESC
                """);
    }

    private static String extractText(byte[] content, String filename) {
        String name = filename == null ? "" : filename.toLowerCase();
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            return new String(content, StandardCharsets.UTF_8);
        }
        var resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        var reader = new PagePdfDocumentReader(resource, PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .build());
        return reader.get().stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    private static Map<String, Object> metadata(SectionChunk c, String versionLabel, String sourceUrl, String ingestionId) {
        Map<String, Object> md = new HashMap<>();
        md.put("act", c.act());
        md.put("chapter", c.chapter());
        md.put("chapterTitle", c.chapterTitle());
        md.put("section", c.section());
        md.put("heading", c.heading());
        md.put("subsections", c.subsections());
        md.put("versionLabel", versionLabel);
        md.put("sourceUrl", sourceUrl == null ? "" : sourceUrl);
        md.put("ingestionId", ingestionId);
        return md;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
