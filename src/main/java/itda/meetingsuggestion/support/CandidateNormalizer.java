package itda.meetingsuggestion.support;

import itda.meetingcard.domain.MeetingCardType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * AI 후보의 canonical 의미 정규화와 Suggestion fingerprint.
 *
 * <p>AI 원시 응답에는 안정적인 후보 ID 가 없으므로 mapper 이후 canonical candidate
 * 의미({@code cardType/date/time/place})를 정규화해 결정적 fingerprint 를 만든다.
 * 배열 순서나 retry 응답 순서가 바뀌어도 같은 의미 후보는 같은 fingerprint 다.
 *
 * <p>정규화는 과잉 fuzzy matching 없이 최소한만 한다.
 * <ul>
 *   <li>date: ISO 파싱 가능하면 {@code LocalDate.toString()} (yyyy-MM-dd), 아니면 trim 한 원문</li>
 *   <li>time: ISO 파싱 가능하면 {@code HH:mm} (기존 CardDraft 정규화와 동일), 아니면 trim 한 원문</li>
 *   <li>place: trim + {@code meeting_suggestions.place_text} 폭(500) 자르기</li>
 * </ul>
 */
public final class CandidateNormalizer {

    /** {@code meeting_suggestions.place_text} 컬럼 폭. */
    public static final int MAX_PLACE_LENGTH = 500;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    /** 값 구분자. 정규화된 값에 등장하지 않는 제어 문자로 컴포넌트 경계를 고정한다. */
    private static final String SEPARATOR = "\u001F";

    private CandidateNormalizer() {
    }

    public static String normalizeDate(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return LocalDate.parse(trimmed).toString();
        } catch (RuntimeException unparseable) {
            return trimmed;
        }
    }

    public static String normalizeTime(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return LocalTime.parse(trimmed).format(TIME);
        } catch (RuntimeException unparseable) {
            return trimmed;
        }
    }

    public static String normalizePlace(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() <= MAX_PLACE_LENGTH
                ? trimmed
                : trimmed.substring(0, MAX_PLACE_LENGTH);
    }

    /**
     * Scan + 정규화된 후보 의미의 SHA-256 hex. {@code meeting_suggestions.fingerprint}
     * UNIQUE 의 최종 방어선과 함께 Suggestion 멱등성을 만든다.
     *
     * <p>원문 date/time/place 를 받아 내부에서 정규화한다. 호출부가 정규화를 잊어도
     * fingerprint 의미가 갈라지지 않게 하기 위함이다.
     */
    public static String fingerprint(long scanId,
                                     MeetingCardType cardType,
                                     String rawDate,
                                     String rawTime,
                                     String rawPlace) {
        String canonical = scanId
                + SEPARATOR + (cardType == null ? "" : cardType.name())
                + SEPARATOR + nullToEmpty(normalizeDate(rawDate))
                + SEPARATOR + nullToEmpty(normalizeTime(rawTime))
                + SEPARATOR + nullToEmpty(normalizePlace(rawPlace));
        return HexFormat.of().formatHex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required on every JVM", impossible);
        }
    }
}
