package za.co.complyza.privacy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    // Synthetic ID that passes date + Luhn checks (not a real person)
    static final String VALID_ID = "8001015009087";

    @Test
    void validatesSaIdNumbers() {
        assertThat(PiiRedactor.isValidSaId(VALID_ID)).isTrue();
        assertThat(PiiRedactor.isValidSaId("8001015009088")).isFalse(); // wrong check digit
        assertThat(PiiRedactor.isValidSaId("8013015009087")).isFalse(); // month 13
        assertThat(PiiRedactor.isValidSaId("12345")).isFalse();
    }

    @Test
    void redactsIdPhoneAndEmail() {
        var r = redactor.redact("My ID is " + VALID_ID + ", call 082 555 1234 or +27 21 555 9876, email thabo@example.co.za");
        assertThat(r.text())
                .contains("[SA_ID]", "[PHONE]", "[EMAIL]")
                .doesNotContain(VALID_ID, "082 555 1234", "thabo@example.co.za");
        assertThat(r.counts()).containsEntry("sa_id", 1).containsEntry("phone", 2).containsEntry("email", 1);
    }

    @Test
    void leavesLegalReferencesAlone() {
        var r = redactor.redact("What does section 72 of Act 4 of 2013 say about transfers?");

        assertThat(r.redacted()).isFalse();
        assertThat(r.text()).isEqualTo("What does section 72 of Act 4 of 2013 say about transfers?");
    }

    @Test
    void ignoresThirteenDigitNumbersThatAreNotIds() {
        var r = redactor.redact("Reference 1234567890123");
        assertThat(r.text()).contains("1234567890123");
    }
}
