package za.co.complyza.qa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import za.co.complyza.audit.AuditService;
import za.co.complyza.config.AppProperties;
import za.co.complyza.privacy.PiiRedactor;
import za.co.complyza.search.HybridSearchService;
import za.co.complyza.search.RetrievedSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ComplianceQaService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceQaService.class);

    static final String SYSTEM_PROMPT = """
            You are Comply ZA, an assistant that explains South African regulatory text
            (for example POPIA and the Financial Intelligence Centre Act) to compliance analysts.

            Rules:
            - Answer ONLY from the numbered sources provided. Never use outside knowledge of the law.
            - Support every statement with a citation. A citation has the sourceId exactly as shown
              (for example "S2") and a quote copied word-for-word from that source, 8 to 40 words long.
            - Mention the act and section number in the answer text, e.g. "Section 72 of POPIA ...".
            - If the sources do not answer the question, say so plainly and set confidence to
              INSUFFICIENT_SOURCES. Use LOW if they only partly answer it.
            - Explain what the text says in plain English. Do not give legal advice or tell the user what to do.
            """;

    private static final String NO_SOURCES_ANSWER =
            "I couldn't find sections in the loaded regulations that answer this question. "
                    + "Try rephrasing, name the Act or section, or check that the relevant Act has been ingested.";

    private static final String UNVERIFIED_ANSWER =
            "I found possibly relevant sections (listed below), but couldn't produce an answer whose quotes "
                    + "match the source text exactly, so I'm not showing one. Please read the listed sections directly.";

    private final ChatClient chatClient;
    private final HybridSearchService search;
    private final AuditService audit;
    private final AppProperties props;
    private final PiiRedactor redactor = new PiiRedactor();
    private final CitationVerifier verifier = new CitationVerifier();

    public ComplianceQaService(ChatClient chatClient, HybridSearchService search, AuditService audit, AppProperties props) {
        this.chatClient = chatClient;
        this.search = search;
        this.audit = audit;
        this.props = props;
    }

    public ComplianceResponse ask(String rawQuestion, String username) {
        PiiRedactor.Result redaction = redactor.redact(rawQuestion);
        String question = redaction.text();

        List<RetrievedSection> retrieved = search.search(question);
        Map<String, RetrievedSection> byId = new LinkedHashMap<>();
        for (int i = 0; i < retrieved.size(); i++) {
            byId.put("S" + (i + 1), retrieved.get(i));
        }

        ComplianceResponse response;
        if (retrieved.isEmpty()) {
            response = build(question, NO_SOURCES_ANSWER, Confidence.INSUFFICIENT_SOURCES,
                    List.of(), 0, byId, redaction);
        } else {
            LlmAnswer llm = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage(question, byId))
                    .call()
                    .entity(LlmAnswer.class);

            CitationVerifier.Outcome outcome = verifier.verify(llm == null ? null : llm.citations(), byId);
            if (outcome.rejected() > 0) {
                log.warn("Rejected {} unverifiable citation(s) for question: {}", outcome.rejected(), question);
            }

            if (llm == null || llm.answer() == null) {
                response = build(question, UNVERIFIED_ANSWER, Confidence.INSUFFICIENT_SOURCES,
                        List.of(), outcome.rejected(), byId, redaction);
            } else if (llm.confidence() == Confidence.INSUFFICIENT_SOURCES) {
                response = build(question, llm.answer(), Confidence.INSUFFICIENT_SOURCES,
                        outcome.verified(), outcome.rejected(), byId, redaction);
            } else if (outcome.verified().isEmpty()) {
                // The model answered but none of its quotes are real: don't show the answer.
                response = build(question, UNVERIFIED_ANSWER, Confidence.INSUFFICIENT_SOURCES,
                        List.of(), outcome.rejected(), byId, redaction);
            } else {
                Confidence confidence = outcome.rejected() > 0 && llm.confidence() == Confidence.HIGH
                        ? Confidence.LOW
                        : llm.confidence() == null ? Confidence.LOW : llm.confidence();
                response = build(question, llm.answer(), confidence,
                        outcome.verified(), outcome.rejected(), byId, redaction);
            }
        }

        audit.record(username, question, retrieved, response);
        return response;
    }

    static String userMessage(String question, Map<String, RetrievedSection> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("Question: ").append(question).append("\n\nSources:\n");
        sources.forEach((id, s) -> sb.append("\n[").append(id).append("] ")
                .append(s.label())
                .append(s.heading().isBlank() ? "" : " - " + s.heading())
                .append(s.chapter().isBlank() ? "" : " (Chapter " + s.chapter() + ")")
                .append("\n").append(s.text()).append("\n"));
        return sb.toString();
    }

    private ComplianceResponse build(String question, String answer, Confidence confidence,
                                     List<VerifiedCitation> citations, int rejected,
                                     Map<String, RetrievedSection> byId, PiiRedactor.Result redaction) {
        List<ComplianceResponse.SourceRef> refs = new ArrayList<>();
        byId.forEach((id, s) -> refs.add(new ComplianceResponse.SourceRef(id, s.label(), s.heading(), s.sourceUrl())));
        List<String> asAt = byId.values().stream()
                .map(s -> s.act() + ": " + s.versionLabel())
                .distinct()
                .toList();
        return new ComplianceResponse(question, answer, confidence, citations, rejected, refs, asAt,
                props.disclaimer(), redaction.counts());
    }
}
