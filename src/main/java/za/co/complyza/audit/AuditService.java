package za.co.complyza.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.complyza.qa.ComplianceResponse;
import za.co.complyza.search.RetrievedSection;

import java.util.List;
import java.util.Map;

/**
 * Every question, the sections retrieved, the answer and the model used are stored.
 * Compliance teams need to show how an answer was produced. Questions are stored after PII redaction.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String model;

    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                        @Value("${app.chat-model-label:unknown}") String model) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.model = model;
    }

    public void record(String username, String redactedQuestion, List<RetrievedSection> retrieved, ComplianceResponse response) {
        try {
            List<Map<String, Object>> slim = retrieved.stream()
                    .map(s -> Map.<String, Object>of("id", s.id(), "label", s.label(), "score", s.score()))
                    .toList();
            jdbc.update("""
                    INSERT INTO audit_log (username, question, retrieved, answer, model)
                    VALUES (?, ?, ?::jsonb, ?::jsonb, ?)
                    """,
                    username, redactedQuestion,
                    objectMapper.writeValueAsString(slim),
                    objectMapper.writeValueAsString(response),
                    model);
        } catch (Exception e) {
            // Never fail the user's request because the audit write failed, but make it loud.
            log.error("Audit write failed", e);
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("""
                SELECT id, asked_at, username, question, answer ->> 'confidence' AS confidence, model
                FROM audit_log ORDER BY id DESC LIMIT ?
                """, limit);
    }
}
