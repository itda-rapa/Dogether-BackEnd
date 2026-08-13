package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.StorageDeleteReason;
import itda.media.service.StorageDeleteJobEnqueuer;
import itda.media.service.StorageCleanupProperties;
import itda.pet.domain.Pet;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.repository.SetlogRepository;
import itda.setlog.repository.SetlogUploadRepository;
import itda.user.domain.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SetlogDeleteServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");
    private final SetlogRepository setlogs = mock(SetlogRepository.class);
    private final StorageDeleteJobEnqueuer jobs = mock(StorageDeleteJobEnqueuer.class);
    private final SetlogUploadRepository uploads = mock(SetlogUploadRepository.class);
    private final SetlogDeleteService service = new SetlogDeleteService(
            setlogs, jobs, uploads, Clock.fixed(NOW, ZoneOffset.UTC), properties());

    @Test
    void ownerDeletesUserSetlogAndImmediatelyHidesMediaBeforeStorageDeletion() {
        Fixture fixture = fixture(1L, false, SetlogStatus.VISIBLE);
        when(setlogs.findByIdForDelete(10L)).thenReturn(Optional.of(fixture.setlog()));
        when(uploads.findBySetlog_Id(10L)).thenReturn(Optional.empty());

        service.delete(1L, 10L);

        verify(fixture.setlog()).deleteByAuthor();
        verify(fixture.media()).markDeleted(NOW);
        verify(jobs).enqueue("setlogs/1/video.mp4", "version-1",
                StorageDeleteReason.SETLOG_DELETED, NOW);
    }

    @Test
    void seedSetlogIsForbiddenWithoutMutatingMedia() {
        Fixture fixture = fixture(1L, true, SetlogStatus.VISIBLE);
        when(setlogs.findByIdForDelete(10L)).thenReturn(Optional.of(fixture.setlog()));

        assertError(() -> service.delete(1L, 10L), ErrorCode.SEED_SETLOG_DELETE_FORBIDDEN);

        verify(fixture.media(), never()).markDeleted(NOW);
    }

    @Test
    void foreignSetlogIsForbidden() {
        Fixture fixture = fixture(2L, false, SetlogStatus.VISIBLE);
        when(setlogs.findByIdForDelete(10L)).thenReturn(Optional.of(fixture.setlog()));

        assertError(() -> service.delete(1L, 10L), ErrorCode.SETLOG_NOT_FOUND);
    }

    @Test
    void alreadyDeletedSetlogConflicts() {
        Fixture fixture = fixture(1L, false, SetlogStatus.DELETED_BY_AUTHOR);
        when(setlogs.findByIdForDelete(10L)).thenReturn(Optional.of(fixture.setlog()));

        assertError(() -> service.delete(1L, 10L), ErrorCode.SETLOG_ALREADY_DELETED);
    }

    @Test
    void missingSetlogIsNotFound() {
        when(setlogs.findByIdForDelete(10L)).thenReturn(Optional.empty());

        assertError(() -> service.delete(1L, 10L), ErrorCode.SETLOG_NOT_FOUND);
    }

    private static Fixture fixture(Long ownerId, boolean seed, SetlogStatus status) {
        Setlog setlog = mock(Setlog.class);
        Pet pet = mock(Pet.class);
        User owner = mock(User.class);
        Media media = mock(Media.class);
        when(setlog.isSeed()).thenReturn(seed);
        when(setlog.getStatus()).thenReturn(status);
        when(setlog.getAuthorPet()).thenReturn(pet);
        when(pet.getOwner()).thenReturn(owner);
        when(owner.getId()).thenReturn(ownerId);
        when(setlog.getMedia()).thenReturn(media);
        when(media.getPath()).thenReturn("setlogs/1/video.mp4");
        when(media.getObjectVersionId()).thenReturn("version-1");
        return new Fixture(setlog, media);
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(
                                exception.getErrorCode()).isEqualTo(expected));
    }

    private record Fixture(Setlog setlog, Media media) {}

    private static StorageCleanupProperties properties() {
        return new StorageCleanupProperties(60_000, 100, java.time.Duration.ofMinutes(10), 10,
                java.time.Duration.ofMinutes(20), java.time.Duration.ofMinutes(30),
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofHours(6));
    }
}
