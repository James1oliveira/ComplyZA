package za.co.complyza.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LegalSectionParserTest {

    // Invented sample Act in the layout used by South African Acts:
    // table of contents first, then chapters, headings above "N. (1) ..." bodies.
    static final String SAMPLE = """
            GOVERNMENT GAZETTE
            ARRANGEMENT OF SECTIONS
            1. Definitions
            2. Purpose of Act
            3. Security safeguards
            CHAPTER 1
            DEFINITIONS AND PURPOSE
            Definitions
            1. In this Act, unless the context indicates otherwise—
            "record" means any recorded information regardless of form or medium;
            "responsible party" means a person who determines the purpose of processing.
            Purpose of Act
            2. The purpose of this Act is to give effect to the constitutional right to privacy
            by safeguarding personal information when processed by a responsible party.
            12
            CHAPTER 2
            CONDITIONS
            Security safeguards
            3. (1) A responsible party must secure the integrity and confidentiality of personal
            information in its possession by taking appropriate measures.
            (2) In order to give effect to subsection (1), the responsible party must identify
            all reasonably foreseeable internal and external risks to personal information.
            (3) The responsible party must have due regard to generally accepted information
            security practices and procedures.
            """;
    @Test
    void discardsTableOfContentsAndKeepsRealSections() {
        List<SectionChunk> chunks = new LegalSectionParser().parse(SAMPLE, "SAMPLE");

        assertThat(chunks).extracting(SectionChunk::section).containsExactly("1", "2", "3");
        assertThat(chunks.get(0).text()).contains("\"record\" means");
    }

    @Test
    void readsHeadingsAndChapters() {
        List<SectionChunk> chunks = new LegalSectionParser().parse(SAMPLE, "SAMPLE");
        SectionChunk s3 = chunks.get(2);

        assertThat(s3.heading()).isEqualTo("Security safeguards");
        assertThat(s3.chapter()).isEqualTo("2");
        assertThat(s3.chapterTitle()).isEqualTo("CONDITIONS");
        assertThat(s3.text()).startsWith("SAMPLE section 3: Security safeguards");
    }

    @Test
    void dropsGazetteNoiseAndPageNumbers() {
        List<SectionChunk> chunks = new LegalSectionParser().parse(SAMPLE, "SAMPLE");

        assertThat(chunks).noneMatch(c -> c.text().contains("GOVERNMENT GAZETTE"));
        assertThat(chunks.get(1).text()).doesNotContain("\n12\n");
    }

    @Test
    void splitsLongSectionsAtSubsections() {
        List<SectionChunk> chunks = new LegalSectionParser(200).parse(SAMPLE, "SAMPLE");
        List<SectionChunk> s3 = chunks.stream().filter(c -> c.section().equals("3")).toList();

        assertThat(s3).hasSizeGreaterThan(1);
        assertThat(s3.get(0).subsections()).startsWith("(1)");
        assertThat(s3).allMatch(c -> c.text().startsWith("SAMPLE section 3"));
    }

    // Laws.Africa / LawLibrary layout: "N. Heading" lines, a running page header and footer,
    // and a Schedule of amended laws after the last section.
    static final String LAWLIBRARY = """
            Chapter 1 – Definitions
            1. Definitions
            (1) In this Act "record" means any recorded information regardless of form or medium.
            By LawLibrary, AfricanLII, the Judicial Institute for Africa, and Laws.Africa. Share widely and freely. 2
            Sample Act, 2013 (Act 4 of 2013)                                   South Africa
            (2) A "responsible party" is a person who determines the purpose of processing.
            2. Short title
            This Act is called the Sample Act, 2013, and commences on a date set by the President.
            Schedule
            Laws amended by section 1
            Act 2 of 2000 The amendment of section 72(1) by the substitution of paragraph (a).
            """;

    @Test
    void dropsLawLibraryPageFurnitureAndSchedule() {
        List<SectionChunk> chunks = new LegalSectionParser().parse(LAWLIBRARY, "SAMPLE");

        assertThat(chunks).extracting(SectionChunk::section).containsExactly("1", "2");
        assertThat(chunks).noneMatch(c -> c.text().contains("LawLibrary"));
        assertThat(chunks).noneMatch(c -> c.text().contains("(Act 4 of 2013)"));
        assertThat(chunks.get(1).heading()).isEqualTo("Short title");
        assertThat(chunks.get(1).text()).doesNotContain("Laws amended");
    }

    @Test
    void recognisesHeadingLikeLines() {
        assertThat(LegalSectionParser.looksLikeHeading("Security safeguards")).isTrue();
        assertThat(LegalSectionParser.looksLikeHeading("by taking appropriate measures.")).isFalse();
        assertThat(LegalSectionParser.looksLikeHeading("(2) In order to")).isFalse();
    }
}
