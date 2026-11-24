package za.co.complyza.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import za.co.complyza.search.HybridSearchService;
import za.co.complyza.search.RetrievedSection;

import java.util.List;

/**
 * Tools published over MCP (endpoint: /sse), so other AI assistants can search the regulation
 * index directly. They return verbatim source text; the calling assistant does its own reasoning.
 */
@Component
public class RegulationTools {

    private final HybridSearchService search;

    public RegulationTools(HybridSearchService search) {
        this.search = search;
    }

    public record SectionResult(String act, String section, String heading, String text, String sourceUrl,
                                String versionLabel) {
    }

    @Tool(name = "search_regulation",
            description = "Search South African regulations (e.g. POPIA, FIC Act) and return the most relevant "
                    + "sections verbatim, with act, section number and source URL.")
    public List<SectionResult> searchRegulation(
            @ToolParam(description = "A question or keywords, e.g. 'cross-border transfer of personal information' or 'section 22'")
            String query) {
        return search.search(query).stream().map(RegulationTools::toResult).toList();
    }

    private static SectionResult toResult(RetrievedSection s) {
        return new SectionResult(s.act(), s.section(), s.heading(), s.text(), s.sourceUrl(), s.versionLabel());
    }
}
