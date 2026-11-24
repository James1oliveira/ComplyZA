package za.co.complyza.ingest;

/**
 * One retrievable piece of an Act. Usually a whole section; long sections are split
 * at subsection boundaries and {@code subsections} records which ones (e.g. "(1)-(3)").
 */
public record SectionChunk(
        String act,
        String chapter,
        String chapterTitle,
        String section,
        String heading,
        String subsections,
        String text) {
}
