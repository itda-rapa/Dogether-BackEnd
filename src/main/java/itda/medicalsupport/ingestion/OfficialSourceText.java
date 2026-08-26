package itda.medicalsupport.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.*;

final class OfficialSourceText {
    private OfficialSourceText() {}
    static String plain(String html) { return normalize(html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ").replaceAll("(?s)<[^>]+>", " ").replace("&nbsp;", " ")); }
    static String normalize(String value) { return value == null ? null : value.replaceAll("\\s+", " ").trim(); }
    static Instant dateAtStartOfDay(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (!matcher.find()) return null;
        return LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)))
                .atStartOfDay(ZoneId.of("Asia/Seoul"))
                .toInstant();
    }
    static Instant dateTimeAt(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (!matcher.find()) return null;
        return LocalDateTime.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)),
                        Integer.parseInt(matcher.group(5)))
                .atZone(ZoneId.of("Asia/Seoul"))
                .toInstant();
    }
    static Instant requiredDate(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " missing from official source");
        return value;
    }
    static String required(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " missing from official source"); return normalize(value); }
    static String first(String text, String regex) { Matcher m=Pattern.compile(regex, Pattern.DOTALL).matcher(text); return m.find() ? normalize(m.group(m.groupCount() == 0 ? 0 : 1)) : null; }
    static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    static void requireHtml(OfficialSourceResponse response) { if (response.body()==null || response.body().isBlank()) throw new IllegalArgumentException("empty official source"); if (response.contentType()==null || !response.contentType().toLowerCase(Locale.ROOT).contains("text/html")) throw new IllegalArgumentException("unsupported content type: " + response.contentType()); }
    record Source(String role, String url, String body) {}
    /** 여러 공식 원문(본문 + 지정병원 목록 등)을 하나의 raw identity로 결정적으로 결합한다.
     *  source 역할(role)/각 URL/각 raw content hash와 parserVersion을 모두 구분한다. */
    static String aggregateRawHash(String parserVersion, Source... sources) {
        String[] parts = Arrays.stream(sources)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Source::role).thenComparing(Source::url))
                .map(source -> source.role() + "|" + source.url() + "|" + sha256(source.body()))
                .toArray(String[]::new);
        return sha256(parserVersion + "|" + String.join("|", parts));
    }
}
