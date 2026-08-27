package itda.medicalsupport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import itda.medicalsupport.domain.MedicalSupportRevision;
import itda.medicalsupport.repository.MedicalSupportProgramRepository;
import itda.medicalsupport.repository.MedicalSupportRevisionRepository;
import itda.user.repository.UserRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MedicalSupportReviewServiceTest {

    private final MedicalSupportReviewService service =
            new MedicalSupportReviewService(
                    mock(MedicalSupportRevisionRepository.class),
                    mock(MedicalSupportProgramRepository.class),
                    mock(UserRepository.class));

    @Test
    void prefersLaterSourcePublishedAtWhenBothArePresent() {
        Instant published = Instant.parse("2026-03-01T10:00:00Z");
        MedicalSupportRevision candidate = revision(1L, Instant.parse("2026-03-01T11:00:00Z"), published);
        MedicalSupportRevision current = revision(2L, published, published);

        assertThat(service.isNewer(candidate, current)).isTrue();
        assertThat(service.isNewer(current, candidate)).isFalse();
    }

    @Test
    void comparesFetchedAtWhenSourcePublishedAtIsEqualEvenIfRevisionIdIsSmaller() {
        Instant published = Instant.parse("2026-03-01T10:00:00Z");
        MedicalSupportRevision candidate = revision(1L, published, Instant.parse("2026-03-01T11:00:00Z"));
        MedicalSupportRevision current = revision(2L, published, Instant.parse("2026-03-01T10:00:00Z"));

        assertThat(service.isNewer(candidate, current)).isTrue();
        assertThat(service.isNewer(current, candidate)).isFalse();
    }

    @Test
    void keepsCurrentWhenFetchedAtIsEarlierDespiteEqualSourcePublishedAt() {
        Instant published = Instant.parse("2026-03-01T10:00:00Z");
        MedicalSupportRevision candidate = revision(3L, published, Instant.parse("2026-03-01T09:00:00Z"));
        MedicalSupportRevision current = revision(2L, published, Instant.parse("2026-03-01T10:00:00Z"));

        assertThat(service.isNewer(candidate, current)).isFalse();
        assertThat(service.isNewer(current, candidate)).isTrue();
    }

    @Test
    void fallsBackToFetchedAtWhenEitherSourcePublishedAtIsNull() {
        MedicalSupportRevision candidate = revision(1L, null, Instant.parse("2026-03-01T11:00:00Z"));
        MedicalSupportRevision current = revision(2L, Instant.parse("2026-03-01T10:00:00Z"), Instant.parse("2026-03-01T10:00:00Z"));

        assertThat(service.isNewer(candidate, current)).isTrue();
        assertThat(service.isNewer(current, candidate)).isFalse();
    }

    @Test
    void fallsBackToFetchedAtWhenBothSourcePublishedAtAreNull() {
        MedicalSupportRevision candidate = revision(1L, null, Instant.parse("2026-03-01T11:00:00Z"));
        MedicalSupportRevision current = revision(2L, null, Instant.parse("2026-03-01T10:00:00Z"));

        assertThat(service.isNewer(candidate, current)).isTrue();
        assertThat(service.isNewer(current, candidate)).isFalse();
    }

    @Test
    void breaksTieByRevisionIdWhenSourcePublishedAtAndFetchedAtMatch() {
        Instant timestamp = Instant.parse("2026-03-01T10:00:00Z");
        MedicalSupportRevision smaller = revision(1L, timestamp, timestamp);
        MedicalSupportRevision larger = revision(2L, timestamp, timestamp);

        assertThat(service.isNewer(larger, smaller)).isTrue();
        assertThat(service.isNewer(smaller, larger)).isFalse();
    }

    private MedicalSupportRevision revision(long id, Instant sourcePublishedAt, Instant fetchedAt) {
        MedicalSupportRevision revision = mock(MedicalSupportRevision.class);
        given(revision.getId()).willReturn(id);
        given(revision.getSourcePublishedAt()).willReturn(sourcePublishedAt);
        given(revision.getFetchedAt()).willReturn(fetchedAt);
        return revision;
    }
}
