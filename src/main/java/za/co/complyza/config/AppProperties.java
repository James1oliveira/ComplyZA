package za.co.complyza.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Security security, Retrieval retrieval, String disclaimer) {

    public record Security(String adminPassword, String analystPassword) {
    }

    public record Retrieval(int vectorTopK, double similarityThreshold, int keywordTopK, int finalTopK) {
    }
}
