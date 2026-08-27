package itda.medicalsupport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.medicalsupport.domain.MedicalSupportIngestionOutcome;
import itda.medicalsupport.domain.MedicalSupportRevision;
import itda.medicalsupport.ingestion.MedicalSupportCandidate;
import itda.medicalsupport.ingestion.MedicalSupportSourceAdapter;
import itda.medicalsupport.ingestion.MedicalSupportSourceRegistry;
import itda.medicalsupport.ingestion.OfficialSourceExtractionException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import itda.medicalsupport.repository.MedicalSupportRevisionRepository;

class MedicalSupportIngestionServiceTest {

    @Test
    void persistsFailedAttemptWithAvailableResponseMetadataAndDoesNotCreateRevision() {
        MedicalSupportSourceRegistry registry = mock(MedicalSupportSourceRegistry.class);
        MedicalSupportRevisionCreationService revisions = mock(MedicalSupportRevisionCreationService.class);
        MedicalSupportAttemptService attempts = mock(MedicalSupportAttemptService.class);
        MedicalSupportSourceAdapter source = mock(MedicalSupportSourceAdapter.class);
        given(registry.find("seoul")).willReturn(Optional.of(source));
        given(source.sourceUrl()).willReturn("https://official.example/seoul");
        given(source.collect()).willThrow(new OfficialSourceExtractionException(
                "unsupported content type", "application/pdf", "source-hash", new IllegalArgumentException()));
        MedicalSupportSourceIngestionTransactionService service =
                new MedicalSupportSourceIngestionTransactionService(registry, revisions, attempts);

        MedicalSupportIngestionResult result = service.ingest("seoul");

        assertThat(result.outcome()).isEqualTo(MedicalSupportIngestionOutcome.FAILED);
        verify(attempts).failed("seoul", "https://official.example/seoul", "unsupported content type", "application/pdf", "source-hash");
        verifyNoInteractions(revisions);
    }

    @Test
    void keepsSingleSourceFailureErrorContractAfterAttemptWasSaved() {
        MedicalSupportSourceRegistry registry = mock(MedicalSupportSourceRegistry.class);
        MedicalSupportSourceIngestionTransactionService transactionService =
                mock(MedicalSupportSourceIngestionTransactionService.class);
        given(transactionService.ingest("seoul"))
                .willReturn(new MedicalSupportIngestionResult("seoul", MedicalSupportIngestionOutcome.FAILED, null, false, "parse failed"));
        MedicalSupportIngestionService service = new MedicalSupportIngestionService(registry, transactionService);

        assertThatThrownBy(() -> service.ingest("seoul"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.MEDICAL_SUPPORT_SOURCE_FAILED);
    }

    @Test
    void recordsOnlyFailedAttemptWhenRequiredMetadataIsMissing() {
        MedicalSupportSourceRegistry registry = mock(MedicalSupportSourceRegistry.class);
        MedicalSupportRevisionCreationService revisions = mock(MedicalSupportRevisionCreationService.class);
        MedicalSupportAttemptService attempts = mock(MedicalSupportAttemptService.class);
        MedicalSupportSourceAdapter source = mock(MedicalSupportSourceAdapter.class);
        given(registry.find("seongnam")).willReturn(Optional.of(source));
        given(source.sourceUrl()).willReturn("https://official.example/seongnam");
        given(source.collect()).willThrow(new OfficialSourceExtractionException(
                "source published at missing from official source", "text/html", "source-hash", new IllegalArgumentException()));
        MedicalSupportSourceIngestionTransactionService service =
                new MedicalSupportSourceIngestionTransactionService(registry, revisions, attempts);

        MedicalSupportIngestionResult result = service.ingest("seongnam");

        assertThat(result.outcome()).isEqualTo(MedicalSupportIngestionOutcome.FAILED);
        verify(attempts).failed(eq("seongnam"), eq("https://official.example/seongnam"), contains("source published at missing"), eq("text/html"), eq("source-hash"));
        verifyNoInteractions(revisions);
    }

    @Test
    void preservesCollectedMetadataWhenCommonCandidateValidationFails() {
        MedicalSupportSourceRegistry registry = mock(MedicalSupportSourceRegistry.class);
        MedicalSupportRevisionCreationService revisions = mock(MedicalSupportRevisionCreationService.class);
        MedicalSupportAttemptService attempts = mock(MedicalSupportAttemptService.class);
        MedicalSupportSourceAdapter source = mock(MedicalSupportSourceAdapter.class);
        MedicalSupportCandidate valid = candidate();
        MedicalSupportCandidate invalid = new MedicalSupportCandidate(valid.sourceUrl(), valid.sourceDocumentUrl(), valid.sourceOrganization(), valid.sourcePublishedAt(), valid.fetchedAt(), valid.sourceHash(), valid.contentType(), "", valid.stableSourceProgramId(), valid.regionCode(), valid.regionScope(), valid.regionSidoName(), valid.regionSigunguName(), valid.programYear(), valid.programName(), valid.normalizedProgramName(), valid.summary(), valid.supportAmount(), valid.applicationPeriod(), valid.supportTarget(), valid.supportItems(), valid.applicationMethod(), valid.animalRegistrationCondition(), valid.incomeWelfareCondition(), valid.contact(), valid.hospitalPolicy(), valid.programStatus(), valid.hospitals(), valid.semanticFingerprint());
        given(registry.find("seoul")).willReturn(Optional.of(source));
        given(source.collect()).willReturn(invalid);
        MedicalSupportSourceIngestionTransactionService service = new MedicalSupportSourceIngestionTransactionService(registry, revisions, attempts);

        MedicalSupportIngestionResult result = service.ingest("seoul");

        assertThat(result.outcome()).isEqualTo(MedicalSupportIngestionOutcome.FAILED);
        verify(attempts).failed("seoul", invalid.sourceUrl(), "medical support candidate has missing required fields", invalid.contentType(), invalid.sourceHash());
        verifyNoInteractions(revisions);
    }

    @Test
    void pilotContinuesAfterOneSourceFailsAndReturnsPerSourceOutcomes() {
        MedicalSupportSourceRegistry registry = mock(MedicalSupportSourceRegistry.class);
        MedicalSupportSourceIngestionTransactionService transactionService =
                mock(MedicalSupportSourceIngestionTransactionService.class);
        MedicalSupportSourceAdapter seoul = mock(MedicalSupportSourceAdapter.class);
        MedicalSupportSourceAdapter seongnam = mock(MedicalSupportSourceAdapter.class);
        given(seoul.key()).willReturn("seoul");
        given(seongnam.key()).willReturn("seongnam");
        given(registry.all()).willReturn(List.of(seoul, seongnam));
        given(transactionService.ingest("seoul"))
                .willReturn(new MedicalSupportIngestionResult("seoul", MedicalSupportIngestionOutcome.SUCCEEDED, null, false, null));
        given(transactionService.ingest("seongnam"))
                .willReturn(new MedicalSupportIngestionResult("seongnam", MedicalSupportIngestionOutcome.FAILED, null, false, "parse failed"));
        MedicalSupportIngestionService service = new MedicalSupportIngestionService(registry, transactionService);

        assertThat(service.ingestPilot())
                .extracting(MedicalSupportIngestionResult::outcome)
                .containsExactly(MedicalSupportIngestionOutcome.SUCCEEDED, MedicalSupportIngestionOutcome.FAILED);
        verify(transactionService).ingest("seoul");
        verify(transactionService).ingest("seongnam");
    }

    @Test
    void reusesRevisionWhenTheSameSourceHashIsCollectedAgain() {
        MedicalSupportSourceRegistry registry = mock(MedicalSupportSourceRegistry.class);
        MedicalSupportRevisionCreationService revisions = mock(MedicalSupportRevisionCreationService.class);
        MedicalSupportAttemptService attempts = mock(MedicalSupportAttemptService.class);
        MedicalSupportSourceAdapter source = mock(MedicalSupportSourceAdapter.class);
        MedicalSupportCandidate candidate = candidate();
        MedicalSupportRevision existing = MedicalSupportRevision.pending(candidate);
        given(registry.find("seoul")).willReturn(Optional.of(source));
        given(source.collect()).willReturn(candidate);
        given(source.sourceUrl()).willReturn(candidate.sourceUrl());
        given(revisions.createOrFind(candidate)).willReturn(new MedicalSupportRevisionCreationService.RevisionResolution(existing, false));
        MedicalSupportSourceIngestionTransactionService service =
                new MedicalSupportSourceIngestionTransactionService(registry, revisions, attempts);

        MedicalSupportIngestionResult result = service.ingest("seoul");

        assertThat(result.revision()).isSameAs(existing);
        verify(revisions).createOrFind(candidate);
        verify(attempts).succeeded("seoul", candidate.sourceUrl(), candidate.contentType(), candidate.sourceHash(), existing);
    }

    @Test
    void reusesFallbackIdentityWhenRawHtmlHashChangesButSemanticFingerprintDoesNot() {
        MedicalSupportRevisionRepository repository = mock(MedicalSupportRevisionRepository.class);
        MedicalSupportCandidate candidate = candidate();
        MedicalSupportRevision existing = MedicalSupportRevision.pending(candidate);
        MedicalSupportCandidate chromeChanged = new MedicalSupportCandidate(candidate.sourceUrl(), candidate.sourceDocumentUrl(), candidate.sourceOrganization(), candidate.sourcePublishedAt(), candidate.fetchedAt(), "footer-only-new-hash", candidate.contentType(), candidate.parserVersion(), candidate.stableSourceProgramId(), candidate.regionCode(), candidate.regionScope(), candidate.regionSidoName(), candidate.regionSigunguName(), candidate.programYear(), candidate.programName(), candidate.normalizedProgramName(), candidate.summary(), candidate.supportAmount(), candidate.applicationPeriod(), candidate.supportTarget(), candidate.supportItems(), candidate.applicationMethod(), candidate.animalRegistrationCondition(), candidate.incomeWelfareCondition(), candidate.contact(), candidate.hospitalPolicy(), candidate.programStatus(), candidate.hospitals(), candidate.semanticFingerprint());
        given(repository.findBySourceUrlAndSourceHashAndParserVersion(chromeChanged.sourceUrl(), chromeChanged.sourceHash(), chromeChanged.parserVersion())).willReturn(Optional.empty());
        given(repository.findByFallbackIdentityAndSemanticFingerprint(chromeChanged.sourceOrganization(), chromeChanged.regionScope(), chromeChanged.regionCode(), chromeChanged.normalizedProgramName(), chromeChanged.programYear(), chromeChanged.semanticFingerprint())).willReturn(Optional.of(existing));

        MedicalSupportRevision actual = new MedicalSupportRevisionCreationService(repository).createOrFind(chromeChanged).revision();

        assertThat(actual).isSameAs(existing);
        verify(repository, never()).saveAndFlush(any());
    }

    private MedicalSupportCandidate candidate() {
        return new MedicalSupportCandidate("https://official.example/seoul", null, "서울특별시", Instant.EPOCH,
                Instant.EPOCH, "same-hash", "text/html", "test", null, "11", itda.medicalsupport.domain.MedicalSupportRegionScope.SIDO, "서울특별시", null, 2026,
                "반려동물 진료비 지원", "반려동물 진료비 지원", null, null, null, "대상", "항목", "방법",
                null, null, null, itda.medicalsupport.domain.MedicalSupportHospitalPolicy.NOT_PUBLISHED,
                itda.medicalsupport.domain.MedicalSupportProgramStatus.UNKNOWN, List.of(), "fingerprint");
    }
}
