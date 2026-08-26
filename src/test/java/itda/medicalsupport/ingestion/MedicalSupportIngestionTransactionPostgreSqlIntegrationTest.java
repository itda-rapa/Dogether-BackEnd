package itda.medicalsupport.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

import itda.medicalsupport.domain.MedicalSupportChangeType;
import itda.medicalsupport.domain.MedicalSupportHospitalPolicy;
import itda.medicalsupport.domain.MedicalSupportIngestionOutcome;
import itda.medicalsupport.domain.MedicalSupportProgram;
import itda.medicalsupport.domain.MedicalSupportProgramStatus;
import itda.medicalsupport.domain.MedicalSupportRevision;
import itda.medicalsupport.domain.MedicalSupportReviewStatus;
import itda.medicalsupport.repository.MedicalSupportIngestionAttemptRepository;
import itda.medicalsupport.repository.MedicalSupportProgramRepository;
import itda.medicalsupport.repository.MedicalSupportRevisionRepository;
import itda.medicalsupport.service.MedicalSupportIngestionResult;
import itda.medicalsupport.service.MedicalSupportIngestionService;
import itda.medicalsupport.service.MedicalSupportSourceIngestionTransactionService;
import itda.medicalsupport.service.MedicalSupportReviewService;
import itda.user.domain.Role;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class MedicalSupportIngestionTransactionPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                    DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                            .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private MedicalSupportIngestionService ingestionService;

    @Autowired
    private MedicalSupportSourceIngestionTransactionService transactionService;

    @Autowired
    private MedicalSupportReviewService reviewService;

    @Autowired
    private UserRepository users;

    @Autowired
    private MedicalSupportRevisionRepository revisions;

    @Autowired
    private MedicalSupportIngestionAttemptRepository attempts;

    @Autowired
    private MedicalSupportProgramRepository programs;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private MedicalSupportSourceRegistry registry;

    private static final String SEOUL_HOSPITAL_LIST_URL = "https://news.seoul.go.kr/env/archives/567583";

    @BeforeEach
    void cleanDatabase() {
        reset(registry);
        jdbcTemplate.update("delete from medical_support_ingestion_attempts");
        jdbcTemplate.update("delete from medical_support_revision_hospitals");
        jdbcTemplate.update("update medical_support_programs set current_verified_revision_id = null");
        jdbcTemplate.update("update medical_support_revisions set program_id = null");
        jdbcTemplate.update("delete from medical_support_revisions");
        jdbcTemplate.update("delete from medical_support_programs");
    }

    @Test
    void commitsEachPilotSourceIndependentlyWhenTheNextSourceParserFails() throws Exception {
        String seoulFixture = fixture("seoul-20260306.html");
        String hospitalListFixture = fixture("seoul-hospitals-567583.html");
        String malformedSeongnamFixture = fixture("seongnam-20260330.html")
                .replace("기준 중위소득 120% 이하 돌봄취약가구", "");
        MedicalSupportSourceAdapter seoul = new SeoulMedicalSupportSourceAdapter(
                seoulResponse(seoulFixture, hospitalListFixture));
        MedicalSupportSourceAdapter seongnam = new SeongnamMedicalSupportSourceAdapter(response(malformedSeongnamFixture));
        given(registry.all()).willReturn(List.of(seoul, seongnam));
        given(registry.find("seoul")).willReturn(Optional.of(seoul));
        given(registry.find("seongnam")).willReturn(Optional.of(seongnam));

        List<MedicalSupportIngestionResult> results = ingestionService.ingestPilot();

        assertThat(results).extracting(MedicalSupportIngestionResult::outcome)
                .containsExactly(MedicalSupportIngestionOutcome.SUCCEEDED, MedicalSupportIngestionOutcome.FAILED);
        String expectedSeoulHash = OfficialSourceText.aggregateRawHash("seoul-article-v2",
                new OfficialSourceText.Source("main", SeoulMedicalSupportSourceAdapter.URL, seoulFixture),
                new OfficialSourceText.Source("hospital-list", SEOUL_HOSPITAL_LIST_URL, hospitalListFixture));
        assertThat(revisions.findBySourceUrlAndSourceHashAndParserVersion(SeoulMedicalSupportSourceAdapter.URL,
                expectedSeoulHash, "seoul-article-v2"))
                .isPresent();
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(attempts.countBySourceKeyAndOutcome("seoul", MedicalSupportIngestionOutcome.SUCCEEDED))
                .isEqualTo(1);
        assertThat(attempts.countBySourceKeyAndOutcome("seongnam", MedicalSupportIngestionOutcome.FAILED))
                .isEqualTo(1);
    }

    @Test
    void createsNewRevisionWhenDesignatedHospitalListSemanticChanges() throws Exception {
        String article = fixture("seoul-20260306.html");
        String hospitals = fixture("seoul-hospitals-567583.html");
        given(registry.find("seoul")).willReturn(Optional.of(new SeoulMedicalSupportSourceAdapter(
                seoulResponse(article, hospitals))));

        MedicalSupportIngestionResult first = transactionService.ingest("seoul");

        String changedHospitals = hospitals.replace("종로구 자하문로 35-3", "종로구 자하문로 99-1");
        given(registry.find("seoul")).willReturn(Optional.of(new SeoulMedicalSupportSourceAdapter(
                seoulResponse(article, changedHospitals))));

        MedicalSupportIngestionResult second = transactionService.ingest("seoul");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isTrue();
        assertThat(revisions.count()).isEqualTo(2);
    }

    @Test
    void reusesSemanticRevisionWhenHospitalListRawChangesButSemanticValuesDoNot() throws Exception {
        String article = fixture("seoul-20260306.html");
        String hospitals = fixture("seoul-hospitals-567583.html");
        given(registry.find("seoul")).willReturn(Optional.of(new SeoulMedicalSupportSourceAdapter(
                seoulResponse(article, hospitals))));

        MedicalSupportIngestionResult first = transactionService.ingest("seoul");

        String reordered = hospitals.replace(
                "<tr><td>바우미우동물병원</td><td>중구 다산로 175, 명덕빌딩</td><td>02-2237-3366</td></tr>"
                        + "<tr><td>힐스타운동물병원</td><td>중구 다산로 32, 스포츠상가동 106-2호</td><td>02-2237-7582</td></tr>",
                "<tr><td>힐스타운동물병원</td><td>중구 다산로 32, 스포츠상가동 106-2호</td><td>02-2237-7582</td></tr>"
                        + "<tr><td>바우미우동물병원</td><td>중구 다산로 175, 명덕빌딩</td><td>02-2237-3366</td></tr>");
        given(registry.find("seoul")).willReturn(Optional.of(new SeoulMedicalSupportSourceAdapter(
                seoulResponse(article, reordered))));

        MedicalSupportIngestionResult second = transactionService.ingest("seoul");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void recordsFailedAttemptAndKeepsVerifiedSnapshotWhenHospitalListIsEmpty() throws Exception {
        MedicalSupportRevision verifiedRevision = revisions.saveAndFlush(MedicalSupportRevision.pending(
                candidate("existing", "verified-hash")));
        verifiedRevision.verify(1L, MedicalSupportChangeType.NEW);
        MedicalSupportProgram program = programs.saveAndFlush(MedicalSupportProgram.from(verifiedRevision));
        verifiedRevision.attach(program);
        revisions.saveAndFlush(verifiedRevision);
        Long programId = program.getId();
        Long verifiedRevisionId = verifiedRevision.getId();

        String article = fixture("seoul-20260306.html");
        String emptyList = "<html><body><table><tbody></tbody></table></body></html>";
        given(registry.find("seoul")).willReturn(Optional.of(new SeoulMedicalSupportSourceAdapter(
                seoulResponse(article, emptyList))));

        MedicalSupportIngestionResult result = transactionService.ingest("seoul");

        assertThat(result.outcome()).isEqualTo(MedicalSupportIngestionOutcome.FAILED);
        assertThat(attempts.countBySourceKeyAndOutcome("seoul", MedicalSupportIngestionOutcome.FAILED))
                .isEqualTo(1);
        String failureReason = jdbcTemplate.queryForObject(
                "select failure_reason from medical_support_ingestion_attempts where source_key = 'seoul' and outcome = 'FAILED'",
                String.class);
        assertThat(failureReason).contains("designated hospital list empty");
        MedicalSupportProgram reloaded = programs.findById(programId).orElseThrow();
        assertThat(reloaded.getCurrentVerifiedRevision().getId()).isEqualTo(verifiedRevisionId);
        assertThat(revisions.findById(verifiedRevisionId).orElseThrow().getReviewStatus())
                .isEqualTo(MedicalSupportReviewStatus.VERIFIED);
        assertThat(revisions.count()).isEqualTo(1);
    }

    @Test
    void singleSourceFailureDoesNotChangeAnExistingVerifiedProgramOrRevision() {
        MedicalSupportRevision verifiedRevision = revisions.saveAndFlush(MedicalSupportRevision.pending(
                candidate("existing", "verified-hash")));
        verifiedRevision.verify(1L, MedicalSupportChangeType.NEW);
        MedicalSupportProgram program = programs.saveAndFlush(MedicalSupportProgram.from(verifiedRevision));
        verifiedRevision.attach(program);
        revisions.saveAndFlush(verifiedRevision);
        Long programId = program.getId();
        Long verifiedRevisionId = verifiedRevision.getId();

        MedicalSupportSourceAdapter failing = mock(MedicalSupportSourceAdapter.class);
        given(failing.sourceUrl()).willReturn("https://official.example/failing");
        given(failing.collect()).willThrow(new IllegalArgumentException("parser failed"));
        given(registry.find("seongnam")).willReturn(Optional.of(failing));

        MedicalSupportIngestionResult result = transactionService.ingest("seongnam");

        assertThat(result.outcome()).isEqualTo(MedicalSupportIngestionOutcome.FAILED);
        MedicalSupportProgram reloaded = programs.findById(programId).orElseThrow();
        assertThat(reloaded.getCurrentVerifiedRevision().getId()).isEqualTo(verifiedRevisionId);
        assertThat(revisions.findById(verifiedRevisionId).orElseThrow().getReviewStatus())
                .isEqualTo(MedicalSupportReviewStatus.VERIFIED);
        assertThat(revisions.count()).isEqualTo(1);
        assertThat(attempts.countBySourceKeyAndOutcome("seongnam", MedicalSupportIngestionOutcome.FAILED))
                .isEqualTo(1);
    }

    @Test
    void reparsesSameRawDocumentWhenParserVersionChangesAndSemanticContentChanges() {
        MedicalSupportCandidate v1 = candidate("reprocess", "same-raw", "parser-v1", "fingerprint-v1", "source-local-1", "기존 제목");
        MedicalSupportCandidate v2 = candidate("reprocess", "same-raw", "parser-v2", "fingerprint-v2", "source-local-1", "변경된 제목");
        MedicalSupportSourceAdapter source = mock(MedicalSupportSourceAdapter.class);
        given(source.sourceUrl()).willReturn(v1.sourceUrl());
        given(source.collect()).willReturn(v1, v2, v2);
        given(registry.find("reprocess")).willReturn(Optional.of(source));

        MedicalSupportIngestionResult first = transactionService.ingest("reprocess");
        MedicalSupportIngestionResult second = transactionService.ingest("reprocess");
        MedicalSupportIngestionResult third = transactionService.ingest("reprocess");

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isTrue();
        assertThat(third.created()).isFalse();
        assertThat(revisions.count()).isEqualTo(2);
    }

    @Test
    void keepsOneProgramForStableSourceLocalIdentityWhenTitleChangesAfterVerification() {
        MedicalSupportRevision first = revisions.saveAndFlush(MedicalSupportRevision.pending(
                candidate("stable", "raw-1", "v1", "fp-1", "source-local-title", "기존 제목")));
        MedicalSupportProgram program = programs.saveAndFlush(MedicalSupportProgram.from(first));
        first.attach(program);
        first.verify(1L, MedicalSupportChangeType.NEW);
        program.apply(first);
        revisions.saveAndFlush(first);

        MedicalSupportRevision changedTitle = revisions.saveAndFlush(MedicalSupportRevision.pending(
                candidate("stable", "raw-2", "v1", "fp-2", "source-local-title", "변경 제목")));
        assertThat(programs.findBySourceOrganizationAndStableSourceProgramId("테스트시", "source-local-title")).isPresent();
        assertThat(revisions.count()).isEqualTo(2);
    }

    @Test
    void convergesConcurrentRawParserUniqueRaceToOneWinnerAndTwoAttempts() throws Exception {
        MedicalSupportCandidate candidate = candidate("race", "same-raw", "v1", "same-fingerprint", "source-local-race", "경합 사업");
        CyclicBarrier barrier = new CyclicBarrier(2);
        MedicalSupportSourceAdapter source = new MedicalSupportSourceAdapter() {
            public String key() { return "race"; }
            public String sourceUrl() { return candidate.sourceUrl(); }
            public MedicalSupportCandidate collect() {
                try { barrier.await(5, TimeUnit.SECONDS); return candidate; }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }
        };
        given(registry.find("race")).willReturn(Optional.of(source));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> transactionService.ingest("race"));
            var second = pool.submit(() -> transactionService.ingest("race"));
            MedicalSupportIngestionResult one = first.get(15, TimeUnit.SECONDS);
            MedicalSupportIngestionResult two = second.get(15, TimeUnit.SECONDS);
            assertThat(revisions.count()).isEqualTo(1);
            assertThat(one.created()).isNotEqualTo(two.created());
            assertThat(one.revision().getId()).isEqualTo(two.revision().getId());
            assertThat(attempts.countBySourceKeyAndOutcome("race", MedicalSupportIngestionOutcome.SUCCEEDED)).isEqualTo(2);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void leavesCurrentProgramAtNewerRevisionWhenOlderRevisionIsVerifiedLate() {
        User admin = User.register("medical-admin@example.test", "encoded", "관리자", "med#medicaladmin", "1111051500");
        admin.changeRole(Role.ADMIN);
        admin = users.saveAndFlush(admin);
        MedicalSupportRevision older = revisions.saveAndFlush(MedicalSupportRevision.pending(candidateAt(
                "late", "r1", "fp-r1", "source-local-late", "오전 사업", "10만원", Instant.parse("2026-03-01T10:00:00Z"), Instant.parse("2026-03-01T10:00:00Z"))));
        MedicalSupportRevision newer = revisions.saveAndFlush(MedicalSupportRevision.pending(candidateAt(
                "late", "r2", "fp-r2", "source-local-late", "오후 사업", "20만원", Instant.parse("2026-03-01T11:00:00Z"), Instant.parse("2026-03-01T11:00:00Z"))));

        reviewService.verify(admin.getId(), newer.getId());
        reviewService.verify(admin.getId(), older.getId());

        MedicalSupportProgram program = programs.findBySourceOrganizationAndStableSourceProgramId("테스트시", "source-local-late").orElseThrow();
        assertThat(revisions.findById(older.getId()).orElseThrow().getReviewStatus()).isEqualTo(MedicalSupportReviewStatus.VERIFIED);
        assertThat(revisions.findById(newer.getId()).orElseThrow().getReviewStatus()).isEqualTo(MedicalSupportReviewStatus.VERIFIED);
        assertThat(program.getCurrentVerifiedRevision().getId()).isEqualTo(newer.getId());
        assertThat(program.getProgramName()).isEqualTo("오후 사업");
        assertThat(program.getSupportAmount()).isEqualTo("20만원");
    }

    @Test
    void usesFetchedAtWhenPublishedAtIsEqualForLateVerify() {
        User admin = saveAdmin("fetched-order");
        Instant published = Instant.parse("2026-03-01T10:00:00Z");
        MedicalSupportRevision older = revisions.saveAndFlush(MedicalSupportRevision.pending(candidateAt(
                "equal-published", "equal-r1", "equal-fp-r1", "equal-published-id", "첫 제목", "10만원", published,
                Instant.parse("2026-03-01T10:00:00Z"))));
        MedicalSupportRevision newer = revisions.saveAndFlush(MedicalSupportRevision.pending(candidateAt(
                "equal-published", "equal-r2", "equal-fp-r2", "equal-published-id", "둘째 제목", "20만원", published,
                Instant.parse("2026-03-01T11:00:00Z"))));

        reviewService.verify(admin.getId(), newer.getId());
        reviewService.verify(admin.getId(), older.getId());

        MedicalSupportProgram program = programs.findBySourceOrganizationAndStableSourceProgramId(
                "테스트시", "equal-published-id").orElseThrow();
        assertThat(program.getCurrentVerifiedRevision().getId()).isEqualTo(newer.getId());
        assertThat(program.getProgramName()).isEqualTo("둘째 제목");
        assertThat(program.getSupportAmount()).isEqualTo("20만원");
    }

    @Test
    void fallsBackToFetchedAtWhenEitherPublishedAtIsNull() {
        User admin = saveAdmin("null-published-order");
        MedicalSupportRevision older = revisions.saveAndFlush(MedicalSupportRevision.pending(candidateAt(
                "null-published", "null-r1", "null-fp-r1", "null-published-id", "첫 제목", "10만원", null,
                Instant.parse("2026-03-01T10:00:00Z"))));
        MedicalSupportRevision newer = revisions.saveAndFlush(MedicalSupportRevision.pending(candidateAt(
                "null-published", "null-r2", "null-fp-r2", "null-published-id", "둘째 제목", "20만원", Instant.parse("2026-03-01T11:00:00Z"),
                Instant.parse("2026-03-01T11:00:00Z"))));

        reviewService.verify(admin.getId(), newer.getId());
        reviewService.verify(admin.getId(), older.getId());

        MedicalSupportProgram program = programs.findBySourceOrganizationAndStableSourceProgramId(
                "테스트시", "null-published-id").orElseThrow();
        assertThat(program.getCurrentVerifiedRevision().getId()).isEqualTo(newer.getId());
        assertThat(program.getProgramName()).isEqualTo("둘째 제목");
    }

    @Test
    void usesRevisionIdAsTieBreakerWhenPublishedAndFetchedAtMatch() {
        User admin = saveAdmin("revision-id-order");
        Instant timestamp = Instant.parse("2026-03-01T10:00:00Z");
        MedicalSupportRevision smaller = revisions.saveAndFlush(MedicalSupportRevision.pending(candidateAt(
                "tie-break", "tie-r1", "tie-fp-r1", "tie-break-id", "첫 제목", "10만원", timestamp, timestamp)));
        MedicalSupportRevision larger = revisions.saveAndFlush(MedicalSupportRevision.pending(candidateAt(
                "tie-break", "tie-r2", "tie-fp-r2", "tie-break-id", "둘째 제목", "20만원", timestamp, timestamp)));
        assertThat(larger.getId()).isGreaterThan(smaller.getId());

        reviewService.verify(admin.getId(), larger.getId());
        reviewService.verify(admin.getId(), smaller.getId());

        MedicalSupportProgram program = programs.findBySourceOrganizationAndStableSourceProgramId(
                "테스트시", "tie-break-id").orElseThrow();
        assertThat(program.getCurrentVerifiedRevision().getId()).isEqualTo(larger.getId());
        assertThat(program.getProgramName()).isEqualTo("둘째 제목");
    }

    private User saveAdmin(String suffix) {
        User admin = User.register("medical-admin-" + suffix + "@example.test", "encoded", "관리자", "med#" + suffix.replace("-", ""), "1111051500");
        admin.changeRole(Role.ADMIN);
        return users.saveAndFlush(admin);
    }

    private MedicalSupportCandidate candidate(String sourceKey, String hash) {
        return candidate(sourceKey, hash, "test", "fingerprint-" + sourceKey, null, "반려동물 의료지원 " + sourceKey);
    }

    private MedicalSupportCandidate candidate(String sourceKey, String hash, String parserVersion, String fingerprint, String stableId, String title) {
        return candidateAt(sourceKey, hash, fingerprint, stableId, title, "20만원", Instant.parse("2026-03-01T00:00:00Z"), Instant.EPOCH, parserVersion);
    }

    private MedicalSupportCandidate candidateAt(String sourceKey, String hash, String fingerprint, String stableId, String title, String amount, Instant publishedAt, Instant fetchedAt) {
        return candidateAt(sourceKey, hash, fingerprint, stableId, title, amount, publishedAt, fetchedAt, "test");
    }

    private MedicalSupportCandidate candidateAt(String sourceKey, String hash, String fingerprint, String stableId, String title, String amount, Instant publishedAt, Instant fetchedAt, String parserVersion) {
        return new MedicalSupportCandidate(
                "https://official.example/" + sourceKey, null, "테스트시", publishedAt,
                fetchedAt, hash, "text/html", parserVersion, stableId, "11", itda.medicalsupport.domain.MedicalSupportRegionScope.SIDO, "서울특별시", null, 2026,
                title, title, null, amount, null,
                "지원대상", "지원항목", "방문 신청", null, null, null,
                MedicalSupportHospitalPolicy.NOT_PUBLISHED, MedicalSupportProgramStatus.UNKNOWN, List.of(),
                fingerprint);
    }

    private OfficialSourceHttpClient response(String body) {
        return ignored -> new OfficialSourceResponse(body, "text/html;charset=UTF-8", Instant.EPOCH);
    }

    private OfficialSourceHttpClient seoulResponse(String article, String hospitalList) {
        return url -> url.contains("news.seoul.go.kr/env/archives")
                ? new OfficialSourceResponse(hospitalList, "text/html;charset=UTF-8", Instant.EPOCH)
                : new OfficialSourceResponse(article, "text/html;charset=UTF-8", Instant.EPOCH);
    }

    private String fixture(String name) throws Exception {
        try (var input = new ClassPathResource("medical-support/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
