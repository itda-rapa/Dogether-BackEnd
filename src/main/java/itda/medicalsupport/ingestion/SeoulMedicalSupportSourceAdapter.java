package itda.medicalsupport.ingestion;

import itda.medicalsupport.domain.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

final class SeoulMedicalSupportSourceAdapter implements MedicalSupportSourceAdapter {
    static final String URL = "https://mediahub.seoul.go.kr/archives/2017353";
    // 지정병원 목록은 본문의 "총 148개 동물병원 ☞목록 확인" 링크가 연결한 공식 페이지다.
    // 목록 원문 URL은 새 Hospital 컬럼 대신 Revision.sourceDocumentUrl 추적으로 연결한다.
    // raw identity(sourceHash)에는 본문과 지정병원 목록을 모두 포함한다(aggregateRawHash 참고).
    private static final String HOSPITAL_LIST_URL_PATTERN =
            "href=\"(https://news\\.seoul\\.go\\.kr/env/archives/\\d+)\"[^>]*>[^<]*동물병원[^<]*목록[^<]*확인";
    private static final String PARSER_VERSION = "seoul-article-v2";
    private final OfficialSourceHttpClient http;
    SeoulMedicalSupportSourceAdapter(OfficialSourceHttpClient http) { this.http=http; }
    public String key() { return "seoul"; } public String sourceUrl() { return URL; }
    public MedicalSupportCandidate collect() {
        OfficialSourceResponse raw = http.fetch(URL);
        String articleHash = raw.body() == null ? null : OfficialSourceText.sha256(raw.body());
        try {
            OfficialSourceText.requireHtml(raw);
            String text = OfficialSourceText.plain(raw.body());
            Instant publishedAt = OfficialSourceText.requiredDate(
                    OfficialSourceText.dateTimeAt(text,
                            "발행일\\s*(20\\d{2})\\.(\\d{1,2})\\.(\\d{1,2})\\.\\s*(\\d{1,2}):(\\d{2})"),
                    "source published at");
            // programYear는 annual official article adapter의 source-specific 규칙:
            // 발행일(sourcePublishedAt)의 Asia/Seoul 연도만 사용한다.
            // 제목/본문의 첫 20xx, footer/navigation 날짜, 과거 사업 연도는 사용하지 않는다.
            Integer year = publishedAt.atZone(ZoneId.of("Asia/Seoul")).getYear();
            String name = OfficialSourceText.required(OfficialSourceText.first(raw.body(),
                    "<h1[^>]*class=\"tit\"[^>]*>\\s*(.+?)\\s*</h1>"), "program name");
            String target = OfficialSourceText.required(OfficialSourceText.first(text,
                    "신청자격\\s*[:：]\\s*(.+?)(?= 기초생활수급자)"), "target");
            String income = OfficialSourceText.required(OfficialSourceText.first(text,
                    "(기초생활수급자, 차상위계층, 한부모가족)(?= ※)"), "income welfare condition");
            String registration = OfficialSourceText.required(OfficialSourceText.first(text,
                    "(동물 등록된 반려견·반려묘에 한해 지원)"), "animal registration condition");
            String method = OfficialSourceText.required(OfficialSourceText.first(text,
                    "신청방법\\s*[:：]\\s*(.+?)(?= 지원내용)"), "application method");
            String items = OfficialSourceText.required(OfficialSourceText.first(text,
                    "지원내용\\s*[:：]\\s*(.+?)(?= 보호자 부담금)"), "support items");
            String amount = OfficialSourceText.required(OfficialSourceText.first(text,
                    "선택진료\\s*\\(\\s*(\\d+만원\\s*이내)\\s*\\)"), "support amount");
            String contact = OfficialSourceText.required(OfficialSourceText.first(text,
                    "문의\\s*[:：]\\s*(다산콜센터\\s*02-120)"), "contact");
            String hospitalListUrl = OfficialSourceText.first(raw.body(), HOSPITAL_LIST_URL_PATTERN);
            List<MedicalSupportCandidate.Hospital> hospitals = List.of();
            MedicalSupportHospitalPolicy hospitalPolicy = MedicalSupportHospitalPolicy.NOT_PUBLISHED;
            OfficialSourceResponse hospitalList = null;
            if (hospitalListUrl != null) {
                // fail-closed: 링크가 있으면 fetch·parse해 1건 이상 확보해야 DESIGNATED_LIST.
                // fetch 실패 / unsupported content type / malformed / 0건은 OfficialSourceExtractionException으로
                // FAILED Attempt만 남기고 빈 목록을 NOT_PUBLISHED로 둔갑시키지 않는다.
                hospitalList = http.fetch(hospitalListUrl);
                OfficialSourceText.requireHtml(hospitalList);
                hospitals = SeoulHospitalListParser.parse(hospitalList.body());
                if (hospitals.isEmpty()) throw new IllegalArgumentException("designated hospital list empty from official source");
                hospitalPolicy = MedicalSupportHospitalPolicy.DESIGNATED_LIST;
            }
            // aggregate raw identity: 본문과 지정병원 목록을 하나의 서울 Candidate 원문 묶음으로 취급.
            // role/각 URL/각 raw content hash/parserVersion을 모두 구분해 결정적으로 결합한다.
            // 이 계산은 어떤 findBySourceUrlAndSourceHashAndParserVersion 조회보다 먼저 수행된다.
            String sourceHash = OfficialSourceText.aggregateRawHash(PARSER_VERSION,
                    new OfficialSourceText.Source("main", URL, raw.body()),
                    hospitalList == null
                            ? null
                            : new OfficialSourceText.Source("hospital-list", hospitalListUrl, hospitalList.body()));
            String fingerprint = SemanticFingerprint.of(
                    name, String.valueOf(year), MedicalSupportRegionScope.SIDO.name(), "11", target, items, amount, null, method, registration, income,
                    contact, hospitalPolicy.name(), MedicalSupportProgramStatus.UNKNOWN.name(),
                    SemanticFingerprint.hospitals(hospitals));
            return new MedicalSupportCandidate(URL, hospitalListUrl, "서울특별시", publishedAt, raw.fetchedAt(), sourceHash,
                    raw.contentType(), PARSER_VERSION, "seoul-mediahub-2017353", "11", MedicalSupportRegionScope.SIDO,
                    "서울특별시", null, year, name,
                    MedicalSupportCandidate.normalize(name), null, amount, null, target, items, method,
                    registration, income, contact, hospitalPolicy, MedicalSupportProgramStatus.UNKNOWN, hospitals,
                    fingerprint);
        } catch (RuntimeException exception) {
            throw new OfficialSourceExtractionException(
                    exception.getMessage(), raw.contentType(), articleHash, exception);
        }
    }
}
