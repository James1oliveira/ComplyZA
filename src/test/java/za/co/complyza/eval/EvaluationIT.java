package za.co.complyza.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import za.co.complyza.ingest.IngestionService;
import za.co.complyza.qa.ComplianceQaService;
import za.co.complyza.qa.ComplianceResponse;
import za.co.complyza.qa.Confidence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end quality check against the real POPIA text and a real model.
 *
 * Needs: Docker (Testcontainers), Ollama running, RUN_EVAL=true, and data/popia.pdf (English text of Act 4 of 2013).
 * Writes target/eval-report.json with the scores you can quote in the README.
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_EVAL", matches = "true")
class EvaluationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    record GoldenQuestion(String question, List<String> expectedSections) {
    }

    @Autowired
    IngestionService ingestion;
    @Autowired
    ComplianceQaService qa;
    @Autowired
    ChatClient.Builder chatClientBuilder;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void goldenQuestions() throws Exception {
        Path pdf = Path.of("data/popia.pdf");
        assumeTrue(Files.exists(pdf), "Put the English POPIA PDF at data/popia.pdf to run the evaluation");

        ingestion.ingest(Files.readAllBytes(pdf), "popia.pdf", "POPIA", "Act 4 of 2013", null);

        List<GoldenQuestion> questions = objectMapper.readValue(
                new ClassPathResource("eval/golden-questions.json").getInputStream(), new TypeReference<>() {
                });
        RelevancyEvaluator relevancy = new RelevancyEvaluator(chatClientBuilder);

        int sectionHits = 0;
        int relevant = 0;
        int answered = 0;
        int rejectedTotal = 0;
        List<Map<String, Object>> rows = new ArrayList<>();

        for (GoldenQuestion q : questions) {
            ComplianceResponse r = qa.ask(q.question(), "evaluation");
            boolean hit = r.citations().stream().anyMatch(c -> q.expectedSections().contains(c.section()));
            boolean isAnswered = r.confidence() != Confidence.INSUFFICIENT_SOURCES;

            boolean isRelevant = false;
            if (isAnswered) {
                List<Document> context = r.citations().stream().map(c -> new Document(c.quote())).toList();
                isRelevant = relevancy.evaluate(new EvaluationRequest(q.question(), context, r.answer())).isPass();
            }

            sectionHits += hit ? 1 : 0;
            relevant += isRelevant ? 1 : 0;
            answered += isAnswered ? 1 : 0;
            rejectedTotal += r.rejectedCitations();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", q.question());
            row.put("expected", q.expectedSections());
            row.put("cited", r.citations().stream().map(c -> c.section()).distinct().toList());
            row.put("sectionHit", hit);
            row.put("relevant", isRelevant);
            row.put("confidence", r.confidence());
            row.put("rejectedCitations", r.rejectedCitations());
            rows.add(row);
        }

        int n = questions.size();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("questions", n);
        report.put("citationAccuracy", (double) sectionHits / n);
        report.put("answerRelevancy", (double) relevant / n);
        report.put("answeredRate", (double) answered / n);
        report.put("rejectedCitations", rejectedTotal);
        report.put("results", rows);

        Files.createDirectories(Path.of("target"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/eval-report.json").toFile(), report);
        System.out.printf("%nEVALUATION: citation accuracy %.0f%%, relevancy %.0f%%, answered %.0f%% (%d questions)%n",
                100.0 * sectionHits / n, 100.0 * relevant / n, 100.0 * answered / n, n);

        assertThat((double) sectionHits / n).as("citation accuracy").isGreaterThanOrEqualTo(0.7);
        assertThat((double) relevant / n).as("answer relevancy").isGreaterThanOrEqualTo(0.7);
    }
}
