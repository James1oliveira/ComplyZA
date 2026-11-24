package za.co.complyza.privacy;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes South African personal information from user questions before they are sent
 * to an external model or written to the audit log (POPIA condition 7: security safeguards).
 *
 * Detects:
 *  - SA ID numbers (13 digits, valid YYMMDD date, valid Luhn check digit)
 *  - SA phone numbers (+27 / 0027 / 0 followed by 9 digits, spaces or dashes allowed)
 *  - Email addresses
 */
public class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern THIRTEEN_DIGITS = Pattern.compile("(?<!\\d)\\d{13}(?!\\d)");
    private static final Pattern SA_PHONE = Pattern.compile(
            "(?<![\\d+])(?:\\+27|0027|0)[\\s-]?\\d{2}[\\s-]?\\d{3}[\\s-]?\\d{4}(?!\\d)");

    public record Result(String text, Map<String, Integer> counts) {
        public boolean redacted() {
            return !counts.isEmpty();
        }
    }

    public Result redact(String input) {
        if (input == null || input.isBlank()) {
            return new Result(input, Map.of());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        String text = input;

        text = replace(EMAIL, text, "[EMAIL]", "email", counts);

        Matcher m = THIRTEEN_DIGITS.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (isValidSaId(m.group())) {
                counts.merge("sa_id", 1, Integer::sum);
                m.appendReplacement(sb, "[SA_ID]");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        text = sb.toString();

        text = replace(SA_PHONE, text, "[PHONE]", "phone", counts);
        return new Result(text, counts);
    }

    private static String replace(Pattern p, String text, String token, String key, Map<String, Integer> counts) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            counts.merge(key, 1, Integer::sum);
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** YYMMDD SSSS C A Z: valid birth date, citizenship digit 0/1/2, Luhn check digit. */
    public static boolean isValidSaId(String id) {
        if (id == null || !id.matches("\\d{13}")) {
            return false;
        }
        int yy = Integer.parseInt(id.substring(0, 2));
        int mm = Integer.parseInt(id.substring(2, 4));
        int dd = Integer.parseInt(id.substring(4, 6));
        try {
            LocalDate.of(2000 + yy, mm, dd); // 2000 is a leap year, so 29 Feb always validates
        } catch (DateTimeException e) {
            return false;
        }
        char citizenship = id.charAt(10);
        if (citizenship != '0' && citizenship != '1' && citizenship != '2') {
            return false;
        }
        return luhnValid(id);
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
