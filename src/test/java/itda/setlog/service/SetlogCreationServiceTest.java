package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.repository.MediaRepository;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.dto.SetlogCreateRequest;
import itda.setlog.dto.SetlogCreateResponse;
import itda.setlog.repository.SetlogRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SetlogCreationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long PET_ID = 10L;
    private static final Long MEDIA_ID = 20L;
    private static final Long SETLOG_ID = 30L;

    @Mock
    private SetlogRepository setlogRepository;
    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private PetRepository petRepository;
    @Mock
    private ActivePetQueryService activePetQueryService;

    private SetlogCreationService setlogCreationService;

    @BeforeEach
    void setUp() {
        setlogCreationService = new SetlogCreationService(
                setlogRepository,
                mediaRepository,
                petRepository,
                activePetQueryService
        );
    }

    @Test
    void createsVisibleSeedSetlogFromUploadedVideo() {
        Pet pet = mock(Pet.class);
        Media media = playableVideo();
        Instant createdAt = Instant.parse("2026-07-30T01:00:00Z");
        stubActivePet(pet);
        given(pet.getId()).willReturn(PET_ID);
        given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                .willReturn(Optional.of(media));
        given(setlogRepository.existsByMedia_Id(MEDIA_ID))
                .willReturn(false);
        given(setlogRepository.save(any(Setlog.class)))
                .willAnswer(invocation -> {
                    Setlog setlog = invocation.getArgument(0);
                    ReflectionTestUtils.setField(
                            setlog,
                            "id",
                            SETLOG_ID
                    );
                    ReflectionTestUtils.setField(
                            setlog,
                            "createdAt",
                            createdAt
                    );
                    return setlog;
                });

        SetlogCreateResponse result = setlogCreationService.create(
                USER_ID,
                new SetlogCreateRequest(MEDIA_ID, " 같이 놀아요 ")
        );

        assertThat(result.setlogId()).isEqualTo(SETLOG_ID);
        assertThat(result.authorPetId()).isEqualTo(PET_ID);
        assertThat(result.mediaId()).isEqualTo(MEDIA_ID);
        assertThat(result.caption()).isEqualTo("같이 놀아요");
        assertThat(result.status()).isEqualTo(SetlogStatus.VISIBLE);
        assertThat(result.createdAt()).isEqualTo(createdAt);

        ArgumentCaptor<Setlog> captor =
                ArgumentCaptor.forClass(Setlog.class);
        then(setlogRepository).should().save(captor.capture());
        assertThat(captor.getValue().isSeed()).isTrue();
        assertThat(captor.getValue().getCuteCount()).isZero();
        assertThat(captor.getValue().getLikeCount()).isZero();
    }

    @Test
    void rejectsMediaOwnedByAnotherUser() {
        Pet pet = mock(Pet.class);
        Media media = mock(Media.class);
        stubActivePet(pet);
        given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                .willReturn(Optional.of(media));
        given(media.getUserId()).willReturn(999L);

        assertThatThrownBy(() -> setlogCreationService.create(
                USER_ID,
                new SetlogCreateRequest(MEDIA_ID, null)
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_OWNED)
        );

        then(setlogRepository).should(never()).save(any());
    }

    @Test
    void rejectsMediaThatHasNotFinishedUploading() {
        Pet pet = mock(Pet.class);
        Media media = mock(Media.class);
        stubActivePet(pet);
        given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                .willReturn(Optional.of(media));
        given(media.getUserId()).willReturn(USER_ID);
        given(media.getMediaType()).willReturn(MediaType.VIDEO);
        given(media.getStatus()).willReturn(MediaStatus.INIT);

        assertThatThrownBy(() -> setlogCreationService.create(
                USER_ID,
                new SetlogCreateRequest(MEDIA_ID, null)
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_UPLOADED)
        );

        then(setlogRepository).should(never()).save(any());
    }

    @Test
    void rejectsMediaAlreadyUsedByAnotherSetlog() {
        Pet pet = mock(Pet.class);
        Media media = playableVideo();
        stubActivePet(pet);
        given(mediaRepository.findByIdAndDeletedAtIsNull(MEDIA_ID))
                .willReturn(Optional.of(media));
        given(setlogRepository.existsByMedia_Id(MEDIA_ID))
                .willReturn(true);

        assertThatThrownBy(() -> setlogCreationService.create(
                USER_ID,
                new SetlogCreateRequest(MEDIA_ID, null)
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.SETLOG_MEDIA_ALREADY_USED
                        )
        );

        then(setlogRepository).should(never()).save(any());
    }

    private void stubActivePet(Pet pet) {
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(new ActivePetContext(
                        PET_ID,
                        USER_ID,
                        "몽이#A7K2",
                        "몽이",
                        null,
                        false
                ));
        given(petRepository.findById(PET_ID))
                .willReturn(Optional.of(pet));
    }

    private Media playableVideo() {
        Media media = mock(Media.class);
        given(media.getId()).willReturn(MEDIA_ID);
        given(media.getUserId()).willReturn(USER_ID);
        given(media.getMediaType()).willReturn(MediaType.VIDEO);
        given(media.getStatus()).willReturn(MediaStatus.UPLOADED);
        return media;
    }
}
