package itda.medicalsupport.ingestion;

import itda.medicalsupport.domain.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

final class SeongnamMedicalSupportSourceAdapter implements MedicalSupportSourceAdapter {
    static final String URL = "https://snvision.seongnam.go.kr/22551";
    private final OfficialSourceHttpClient http;
    SeongnamMedicalSupportSourceAdapter(OfficialSourceHttpClient http) { this.http=http; }
    public String key(){return "seongnam";} public String sourceUrl(){return URL;}
    public MedicalSupportCandidate collect(){
        OfficialSourceResponse raw = http.fetch(URL);
        String sourceHash = raw.body() == null ? null : OfficialSourceText.sha256(raw.body());
        try {
            OfficialSourceText.requireHtml(raw);
            String text = OfficialSourceText.plain(raw.body());
            Instant publishedAt = OfficialSourceText.requiredDate(
                    OfficialSourceText.dateTimeAt(text,
                            "기사입력\\s*(20\\d{2})/(\\d{1,2})/(\\d{1,2})\\s*\\[(\\d{1,2}):(\\d{2})\\]"),
                    "source published at");
            // programYear는 annual official article adapter의 source-specific 규칙:
            // 기사입력(sourcePublishedAt)의 Asia/Seoul 연도만 사용한다.
            // 제목/본문의 첫 20xx, footer/navigation 날짜, 과거 사업 연도는 사용하지 않는다.
            Integer year = publishedAt.atZone(ZoneId.of("Asia/Seoul")).getYear();
            String name = OfficialSourceText.required(OfficialSourceText.first(raw.body(),
                    "<h1[^>]*class=['\"]read_title['\"][^>]*>\\s*(.+?)\\s*</h1>"), "program name");
            String amount = OfficialSourceText.required(OfficialSourceText.first(text,
                    "지원 내용과 사업량은 (.+?)(?=\\. 의료 분야)"), "support amount");
            String items = OfficialSourceText.required(OfficialSourceText.first(text,
                    "의료 분야 지원 범위는 (.+?)(?=\\.)"), "support items");
            String target = OfficialSourceText.required(OfficialSourceText.first(text,
                    "신청 자격은 (.+?)(?=\\. 지원 신청은)"), "target");
            String registration = OfficialSourceText.required(OfficialSourceText.first(text,
                    "(내장형 동물등록을 완료한 반려동물)"), "animal registration condition");
            String income = OfficialSourceText.required(OfficialSourceText.first(text,
                    "(기준 중위소득\\s*\\d+% 이하 돌봄취약가구)"), "income welfare condition");
            String period = OfficialSourceText.required(OfficialSourceText.first(text,
                    "(사업량\\(180 마리\\) 소진 시까지)"), "application period");
            String method = OfficialSourceText.required(OfficialSourceText.first(text,
                    "(성남시농업기술센터 동물보호팀\\(☏031-729-3287\\)을 방문 신청해야 한다)"),
                    "application method");
            String contact = OfficialSourceText.required(OfficialSourceText.first(text,
                    "(☏031-729-3287)"), "contact");
            List<MedicalSupportCandidate.Hospital> hospitals = List.of();
            String fingerprint = SemanticFingerprint.of(
                    name, String.valueOf(year), MedicalSupportRegionScope.SIGUNGU.name(), "41130", target, items, amount, period, method, registration, income,
                    contact, MedicalSupportHospitalPolicy.NOT_PUBLISHED.name(), MedicalSupportProgramStatus.UNKNOWN.name(),
                    SemanticFingerprint.hospitals(hospitals));
            return new MedicalSupportCandidate(URL, null, "성남시", publishedAt, raw.fetchedAt(), sourceHash,
                    raw.contentType(), "seongnam-article-v2", "seongnam-snvision-22551", "41130", MedicalSupportRegionScope.SIGUNGU,
                    "경기도", "성남시", year, name,
                    MedicalSupportCandidate.normalize(name), null, amount, period, target, items, method,
                    registration, income, contact, MedicalSupportHospitalPolicy.NOT_PUBLISHED,
                    MedicalSupportProgramStatus.UNKNOWN, hospitals, fingerprint);
        } catch (RuntimeException exception) {
            throw new OfficialSourceExtractionException(
                    exception.getMessage(), raw.contentType(), sourceHash, exception);
        }
    }
}
