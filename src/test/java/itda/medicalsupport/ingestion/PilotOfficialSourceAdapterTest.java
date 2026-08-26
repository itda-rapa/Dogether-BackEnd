package itda.medicalsupport.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.medicalsupport.domain.MedicalSupportHospitalPolicy;
import itda.medicalsupport.domain.MedicalSupportProgramStatus;
import itda.medicalsupport.domain.MedicalSupportRegionScope;
import itda.medicalsupport.domain.MedicalSupportRevision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PilotOfficialSourceAdapterTest {

    @Test
    void extractsEverySeoulFieldFromTheOfficialFixture() throws Exception {
        MedicalSupportCandidate result = seoul(resource("seoul-20260306.html"));

        assertThat(result.programName()).isEqualTo("반려동물 진료비 부담 덜어드려요! 취약계층에 최대 20만원 지원");
        assertThat(result.programYear()).isEqualTo(2026);
        assertThat(result.sourcePublishedAt()).isEqualTo(Instant.parse("2026-03-06T06:31:00Z"));
        assertThat(result.regionScope()).isEqualTo(MedicalSupportRegionScope.SIDO);
        assertThat(result.regionCode()).isEqualTo("11");
        assertThat(result.supportTarget()).isEqualTo("서울에 주민등록을 두고, 개나 고양이를 기르는");
        assertThat(result.supportItems()).contains("기초건강검진", "심장사상충 예방약");
        assertThat(result.supportAmount()).isEqualTo("20만원 이내");
        assertThat(result.applicationPeriod()).isNull();
        assertThat(result.applicationMethod()).contains("우리동네 동물병원");
        assertThat(result.animalRegistrationCondition()).isEqualTo("동물 등록된 반려견·반려묘에 한해 지원");
        assertThat(result.incomeWelfareCondition()).isEqualTo("기초생활수급자, 차상위계층, 한부모가족");
        assertThat(result.contact()).isEqualTo("다산콜센터 02-120");
        assertThat(result.hospitalPolicy()).isEqualTo(MedicalSupportHospitalPolicy.DESIGNATED_LIST);
        assertThat(result.programStatus()).isEqualTo(MedicalSupportProgramStatus.UNKNOWN);
        assertThat(result.sourceDocumentUrl()).isEqualTo("https://news.seoul.go.kr/env/archives/567583");
        assertThat(result.hospitals()).extracting(MedicalSupportCandidate.Hospital::name)
                .containsExactly("광화문동물병원", "누리봄동물병원", "웰니스클리닉(청계천점)", "바우미우동물병원", "힐스타운동물병원");
        assertThat(result.hospitals().get(0).sigunguName()).isEqualTo("종로구");
        assertThat(result.hospitals().get(0).address()).isEqualTo("종로구 자하문로 35-3");
        assertThat(result.hospitals().get(0).phone()).isEqualTo("02-722-8275");
        assertThat(result.hospitals().get(2).sigunguName()).isEqualTo("중구");
    }

    @Test
    void extractsEverySeongnamFieldFromTheOfficialFixture() throws Exception {
        MedicalSupportCandidate result = seongnam(resource("seongnam-20260330.html"));

        assertThat(result.programName()).isEqualTo("성남시, 반려동물 의료서비스 지원사업 확대");
        assertThat(result.programYear()).isEqualTo(2026);
        assertThat(result.sourcePublishedAt()).isEqualTo(Instant.parse("2026-03-29T22:42:00Z"));
        assertThat(result.regionScope()).isEqualTo(MedicalSupportRegionScope.SIGUNGU);
        assertThat(result.regionCode()).isEqualTo("41130");
        assertThat(result.supportTarget()).contains("실제 거주자", "국가봉사동물 입양자");
        assertThat(result.supportItems()).contains("백신 접종비", "중성화 수술비");
        assertThat(result.supportAmount()).contains("최대 16만 원", "최대 32만 원");
        assertThat(result.applicationPeriod()).isEqualTo("사업량(180 마리) 소진 시까지");
        assertThat(result.applicationMethod()).contains("방문 신청해야 한다");
        assertThat(result.animalRegistrationCondition()).isEqualTo("내장형 동물등록을 완료한 반려동물");
        assertThat(result.incomeWelfareCondition()).isEqualTo("기준 중위소득 120% 이하 돌봄취약가구");
        assertThat(result.contact()).isEqualTo("☏031-729-3287");
        assertThat(result.hospitalPolicy()).isEqualTo(MedicalSupportHospitalPolicy.NOT_PUBLISHED);
        assertThat(result.hospitals()).isEmpty();
    }

    @Test
    void changesFingerprintWhenAnExtractedAmountOrIncomeConditionChanges() throws Exception {
        String seoul = resource("seoul-20260306.html");
        MedicalSupportCandidate originalSeoul = seoul(seoul);
        assertThat(seoul(seoul.replace("20만원 이내", "30만원 이내")).semanticFingerprint())
                .isNotEqualTo(originalSeoul.semanticFingerprint());
        assertThat(seoul(seoul.replace("서울에 주민등록을 두고", "서울에 실제 거주하는")).semanticFingerprint())
                .isNotEqualTo(originalSeoul.semanticFingerprint());

        String seongnam = resource("seongnam-20260330.html");
        MedicalSupportCandidate originalSeongnam = seongnam(seongnam);
        assertThat(seongnam(seongnam.replace("최대 16만 원", "최대 17만 원")).semanticFingerprint())
                .isNotEqualTo(originalSeongnam.semanticFingerprint());
        assertThat(seongnam(seongnam.replace("실제 거주자", "실제 주민")).semanticFingerprint())
                .isNotEqualTo(originalSeongnam.semanticFingerprint());
        assertThat(seongnam(seongnam.replace("기준 중위소득 120% 이하", "기준 중위소득 130% 이하"))
                .semanticFingerprint()).isNotEqualTo(originalSeongnam.semanticFingerprint());
    }

    @Test
    void ignoresUnrelatedNavigationWhitespaceAndPastDates() throws Exception {
        String seoul = resource("seoul-20260306.html");
        MedicalSupportCandidate original = seoul(seoul);
        MedicalSupportCandidate presentationOnly = seoul(seoul.replace("</article>",
                "<footer>  2024.01.01  unrelated navigation   </footer></article>"));

        assertThat(presentationOnly.semanticFingerprint()).isEqualTo(original.semanticFingerprint());
        assertThat(presentationOnly.programYear()).isEqualTo(original.programYear());
        assertThat(presentationOnly.sourcePublishedAt()).isEqualTo(original.sourcePublishedAt());

        String seongnam = resource("seongnam-20260330.html");
        MedicalSupportCandidate originalSeongnam = seongnam(seongnam);
        MedicalSupportCandidate presentationOnlySeongnam = seongnam(seongnam.replace("</article>",
                "<footer>  2023/01/01  unrelated navigation   </footer></article>"));
        assertThat(presentationOnlySeongnam.semanticFingerprint())
                .isEqualTo(originalSeongnam.semanticFingerprint());
        assertThat(presentationOnlySeongnam.programYear()).isEqualTo(originalSeongnam.programYear());
        assertThat(presentationOnlySeongnam.sourcePublishedAt()).isEqualTo(originalSeongnam.sourcePublishedAt());
    }

    @Test
    void derivesProgramYearAndPublishedAtFromTheOfficialDateTimeField() throws Exception {
        String seoul = resource("seoul-20260306.html");
        MedicalSupportCandidate changed = seoul(seoul.replace("발행일 2026.03.06. 15:31", "발행일 2027.04.07. 15:31"));

        assertThat(changed.sourcePublishedAt()).isEqualTo(Instant.parse("2027-04-07T06:31:00Z"));
        assertThat(changed.programYear()).isEqualTo(2027);

        String seongnam = resource("seongnam-20260330.html");
        MedicalSupportCandidate changedSeongnam = seongnam(seongnam
                .replace("기사입력 2026/03/30 [07:42]", "기사입력 2027/04/07 [07:42]"));
        assertThat(changedSeongnam.sourcePublishedAt()).isEqualTo(Instant.parse("2027-04-06T22:42:00Z"));
        assertThat(changedSeongnam.programYear()).isEqualTo(2027);
    }

    @Test
    void titleChangeAltersOnlyProgramNameAndFingerprint() throws Exception {
        String seoul = resource("seoul-20260306.html");
        MedicalSupportCandidate original = seoul(seoul);
        MedicalSupportCandidate changed = seoul(seoul.replace("진료비 부담 덜어드려요", "진료비 부담 줄여드려요"));

        assertThat(changed.programName()).isEqualTo("반려동물 진료비 부담 줄여드려요! 취약계층에 최대 20만원 지원");
        assertThat(changed.programYear()).isEqualTo(original.programYear());
        assertThat(changed.sourcePublishedAt()).isEqualTo(original.sourcePublishedAt());
        assertThat(changed.semanticFingerprint()).isNotEqualTo(original.semanticFingerprint());

        String seongnam = resource("seongnam-20260330.html");
        MedicalSupportCandidate originalSeongnam = seongnam(seongnam);
        MedicalSupportCandidate changedSeongnam = seongnam(seongnam
                .replace("반려동물 의료서비스 지원사업 확대", "반려동물 의료서비스 지원사업 개편"));
        assertThat(changedSeongnam.programName()).isEqualTo("성남시, 반려동물 의료서비스 지원사업 개편");
        assertThat(changedSeongnam.programYear()).isEqualTo(originalSeongnam.programYear());
        assertThat(changedSeongnam.sourcePublishedAt()).isEqualTo(originalSeongnam.sourcePublishedAt());
        assertThat(changedSeongnam.semanticFingerprint()).isNotEqualTo(originalSeongnam.semanticFingerprint());
    }

    @Test
    void changesRawIdentityAndFingerprintWhenHospitalIsAddedRemovedOrAddressOrPhoneChanges() throws Exception {
        String article = resource("seoul-20260306.html");
        String hospitals = resource("seoul-hospitals-567583.html");
        MedicalSupportCandidate original = seoul(article, hospitals);

        MedicalSupportCandidate addressChanged = seoul(article, hospitals
                .replace("종로구 자하문로 35-3", "종로구 자하문로 99-1"));
        assertThat(addressChanged.sourceHash()).isNotEqualTo(original.sourceHash());
        assertThat(addressChanged.semanticFingerprint()).isNotEqualTo(original.semanticFingerprint());

        MedicalSupportCandidate phoneChanged = seoul(article, hospitals
                .replace("02-722-8275", "02-999-9999"));
        assertThat(phoneChanged.sourceHash()).isNotEqualTo(original.sourceHash());
        assertThat(phoneChanged.semanticFingerprint()).isNotEqualTo(original.semanticFingerprint());

        MedicalSupportCandidate added = seoul(article, hospitals.replace("</tbody>",
                "<tr><td rowspan=\"1\">용산구</td><td>이태원동물병원</td><td>용산구 녹사평대로 210</td><td>02-797-6677</td></tr></tbody>"));
        assertThat(added.hospitals()).hasSize(6);
        assertThat(added.sourceHash()).isNotEqualTo(original.sourceHash());
        assertThat(added.semanticFingerprint()).isNotEqualTo(original.semanticFingerprint());

        MedicalSupportCandidate removed = seoul(article, hospitals
                .replace("<tr><td>누리봄동물병원</td><td>종로구 사직로12길 2</td><td>02-735-7530</td></tr>", ""));
        assertThat(removed.hospitals()).hasSize(4);
        assertThat(removed.sourceHash()).isNotEqualTo(original.sourceHash());
        assertThat(removed.semanticFingerprint()).isNotEqualTo(original.semanticFingerprint());
    }

    @Test
    void keepsFingerprintStableWhenHospitalOrderOrWhitespaceChanges() throws Exception {
        String article = resource("seoul-20260306.html");
        String hospitals = resource("seoul-hospitals-567583.html");
        MedicalSupportCandidate original = seoul(article, hospitals);

        String reordered = hospitals.replace(
                "<tr><td>바우미우동물병원</td><td>중구 다산로 175, 명덕빌딩</td><td>02-2237-3366</td></tr>"
                        + "<tr><td>힐스타운동물병원</td><td>중구 다산로 32, 스포츠상가동 106-2호</td><td>02-2237-7582</td></tr>",
                "<tr><td>힐스타운동물병원</td><td>중구 다산로 32, 스포츠상가동 106-2호</td><td>02-2237-7582</td></tr>"
                        + "<tr><td>바우미우동물병원</td><td>중구 다산로 175, 명덕빌딩</td><td>02-2237-3366</td></tr>");

        MedicalSupportCandidate reorderedCandidate = seoul(article, reordered);
        assertThat(reorderedCandidate.semanticFingerprint()).isEqualTo(original.semanticFingerprint());
        assertThat(reorderedCandidate.sourceHash()).isNotEqualTo(original.sourceHash());

        MedicalSupportCandidate whitespaceChanged = seoul(article, hospitals
                .replace("종로구 자하문로 35-3", "종로구   자하문로   35-3"));
        assertThat(whitespaceChanged.semanticFingerprint()).isEqualTo(original.semanticFingerprint());
        assertThat(whitespaceChanged.sourceHash()).isNotEqualTo(original.sourceHash());
    }

    @Test
    void collectsNotPublishedWhenHospitalListLinkIsMissing() throws Exception {
        String articleWithoutLink = resource("seoul-20260306.html")
                .replace("※ <a href=\"https://news.seoul.go.kr/env/archives/567583\">총 148개 동물병원 ☞목록 확인</a>", "");
        MedicalSupportCandidate result = seoul(articleWithoutLink, resource("seoul-hospitals-567583.html"));

        assertThat(result.hospitalPolicy()).isEqualTo(MedicalSupportHospitalPolicy.NOT_PUBLISHED);
        assertThat(result.hospitals()).isEmpty();
        assertThat(result.sourceDocumentUrl()).isNull();
        assertThat(result.programName()).isEqualTo("반려동물 진료비 부담 덜어드려요! 취약계층에 최대 20만원 지원");
    }

    @Test
    void rejectsWhenHospitalListLinkYieldsNoHospitals() throws Exception {
        String article = resource("seoul-20260306.html");
        String emptyList = "<html><body><table><tbody></tbody></table></body></html>";

        assertThatThrownBy(() -> seoul(article, emptyList))
                .isInstanceOf(OfficialSourceExtractionException.class)
                .hasMessageContaining("designated hospital list empty from official source");
    }

    @Test
    void rejectsWhenHospitalListHasUnsupportedContentType() throws Exception {
        String article = resource("seoul-20260306.html");
        OfficialSourceHttpClient client = url -> url.contains("news.seoul.go.kr/env/archives")
                ? new OfficialSourceResponse("<table/>", "application/pdf", Instant.EPOCH)
                : new OfficialSourceResponse(article, "text/html;charset=UTF-8", Instant.EPOCH);

        assertThatThrownBy(() -> new SeoulMedicalSupportSourceAdapter(client).collect())
                .isInstanceOf(OfficialSourceExtractionException.class)
                .hasMessageContaining("unsupported content type");
    }

    @Test
    void carriesDesignatedHospitalsIntoRevisionSnapshot() throws Exception {
        MedicalSupportCandidate candidate = seoul(resource("seoul-20260306.html"));

        MedicalSupportRevision revision = MedicalSupportRevision.pending(candidate);

        assertThat(candidate.hospitalPolicy()).isEqualTo(MedicalSupportHospitalPolicy.DESIGNATED_LIST);
        assertThat(revision.getHospitals()).extracting(h -> h.getName())
                .containsExactlyElementsOf(candidate.hospitals().stream().map(MedicalSupportCandidate.Hospital::name).toList());
        assertThat(revision.getHospitals()).extracting(h -> h.getSigunguName())
                .containsExactlyElementsOf(candidate.hospitals().stream().map(MedicalSupportCandidate.Hospital::sigunguName).toList());
    }

    @Test
    void rejectsUnsupportedContentTypeAndMissingRequiredBusinessInformation() throws Exception {
        assertThatThrownBy(() -> new SeoulMedicalSupportSourceAdapter(
                ignored -> new OfficialSourceResponse("%PDF", "application/pdf", Instant.EPOCH)).collect())
                .isInstanceOf(OfficialSourceExtractionException.class)
                .hasMessageContaining("unsupported content type");
        assertThatThrownBy(() -> seongnam(resource("seongnam-20260330.html")
                .replace("기준 중위소득 120% 이하 돌봄취약가구", "")))
                .isInstanceOf(OfficialSourceExtractionException.class)
                .hasMessageContaining("income welfare condition missing");
        assertThatThrownBy(() -> seoul(resource("seoul-20260306.html")
                .replace("발행일 2026.03.06. 15:31", "")))
                .isInstanceOf(OfficialSourceExtractionException.class)
                .hasMessageContaining("source published at missing");
        assertThatThrownBy(() -> seongnam(resource("seongnam-20260330.html")
                .replace("기사입력 2026/03/30 [07:42]", "")))
                .isInstanceOf(OfficialSourceExtractionException.class)
                .hasMessageContaining("source published at missing");
    }

    private MedicalSupportCandidate seoul(String article) throws Exception {
        return seoul(article, resource("seoul-hospitals-567583.html"));
    }

    private MedicalSupportCandidate seoul(String article, String hospitalList) {
        return new SeoulMedicalSupportSourceAdapter(seoulClient(article, hospitalList)).collect();
    }

    private MedicalSupportCandidate seongnam(String source) {
        return new SeongnamMedicalSupportSourceAdapter(response(source)).collect();
    }

    private OfficialSourceHttpClient seoulClient(String article, String hospitalList) {
        return url -> url.contains("news.seoul.go.kr/env/archives")
                ? new OfficialSourceResponse(hospitalList, "text/html;charset=UTF-8", Instant.EPOCH)
                : new OfficialSourceResponse(article, "text/html;charset=UTF-8", Instant.EPOCH);
    }

    private OfficialSourceHttpClient response(String source) {
        return ignored -> new OfficialSourceResponse(source, "text/html;charset=UTF-8", Instant.EPOCH);
    }

    private String resource(String resource) throws Exception {
        try (var input = new ClassPathResource("medical-support/" + resource).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
