package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.media.domain.Media;
import itda.media.service.MediaService;
import itda.media.service.MediaService.PresignedDownloadUrl;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.setlog.domain.Setlog;
import itda.setlog.dto.SetlogListResponse;
import itda.setlog.dto.SetlogSource;
import itda.setlog.repository.SetlogReactionRepository;
import itda.setlog.repository.SetlogRepository;
import itda.setlog.support.SetlogCursorCodec;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class SetlogReadServiceTest {

    private static final Long USER_ID = 1L;

    @Mock private SetlogRepository setlogRepository;
    @Mock private SetlogReactionRepository setlogReactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private ActivePetQueryService activePetQueryService;
    @Mock private PetDisplayQueryService petDisplayQueryService;
    @Mock private FriendRelationshipQueryService friendRelationshipQueryService;
    @Mock private MediaService mediaService;

    private SetlogReadService service;

    @BeforeEach
    void setUp() {
        service = new SetlogReadService(
                setlogRepository,
                setlogReactionRepository,
                userRepository,
                activePetQueryService,
                petDisplayQueryService,
                friendRelationshipQueryService,
                mediaService
        );
    }

    @Test
    void emptyPageUsesDefaultLimitAndSkipsBatchWork() {
        activeUser(false);
        given(setlogRepository.findVisibleFeedFirstPage(
                any(), any(), anyList(), any(), any(), any()
        )).willReturn(List.of());

        SetlogListResponse result = service.getSetlogs(USER_ID, null, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(setlogRepository).should().findVisibleFeedFirstPage(
                any(), any(), anyList(), any(), any(), pageable.capture()
        );
        assertThat(pageable.getValue().getPageSize()).isEqualTo(21);
        then(petDisplayQueryService).shouldHaveNoInteractions();
        then(mediaService).shouldHaveNoInteractions();
    }

    @Test
    void limitPlusOneIsTrimmedAndExtraRowDoesNotReceiveMediaUrl() {
        activeUser(false);
        Setlog first = setlog(30L, 130L, 230L, "2026-08-11T03:00:00Z");
        Setlog second = setlog(20L, 120L, 220L, "2026-08-11T02:00:00Z");
        Setlog extra = mock(Setlog.class);
        given(setlogRepository.findVisibleFeedFirstPage(
                any(), any(), anyList(), any(), any(), any()
        )).willReturn(List.of(first, second, extra));
        given(petDisplayQueryService.getPetDisplaySummaries(List.of(130L, 120L)))
                .willReturn(Map.of(
                        130L, petSummary(130L, 3L),
                        120L, petSummary(120L, 2L)
                ));
        given(mediaService.getPresignedDownloadUrls(anyList()))
                .willReturn(Map.of(
                        230L, url("first"),
                        220L, url("second")
                ));

        SetlogListResponse result = service.getSetlogs(USER_ID, null, 2);

        assertThat(result.items()).extracting(item -> item.setlogId())
                .containsExactly(30L, 20L);
        assertThat(result.hasNext()).isTrue();
        assertThat(SetlogCursorCodec.decode(result.nextCursor()))
                .satisfies(cursor -> {
                    assertThat(cursor.setlogId()).isEqualTo(20L);
                    assertThat(cursor.createdAt())
                            .isEqualTo(Instant.parse("2026-08-11T02:00:00Z"));
                });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Media>> mediaItems =
                ArgumentCaptor.forClass(Collection.class);
        then(mediaService).should().getPresignedDownloadUrls(
                mediaItems.capture()
        );
        assertThat(mediaItems.getValue())
                .extracting(Media::getId)
                .containsExactly(230L, 220L);
        then(mediaService).should(never()).getPresignedDownloadUrl(any());
    }

    @Test
    void cursorIsDecodedAndPassedToRepository() {
        activeUser(false);
        Instant createdAt = Instant.parse("2026-08-11T04:00:00Z");
        String cursor = SetlogCursorCodec.encode(77L, createdAt);
        given(setlogRepository.findVisibleFeedAfter(
                any(), any(), anyList(), any(), any(), any(), any(), any()
        )).willReturn(List.of());

        service.getSetlogs(USER_ID, cursor, 5);

        then(setlogRepository).should().findVisibleFeedAfter(
                any(), any(), anyList(), any(), any(),
                org.mockito.ArgumentMatchers.eq(createdAt),
                org.mockito.ArgumentMatchers.eq(77L),
                any()
        );
    }

    @Test
    void activePetUsesPageIdsForBatchLookups() {
        activeUser(true);
        ActivePetContext activePet = new ActivePetContext(
                9L, USER_ID, "내개#ABCD", "내개", null, true
        );
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(activePet);
        Setlog setlog = setlog(30L, 130L, 230L, "2026-08-11T03:00:00Z");
        given(setlogRepository.findVisibleFeedFirstPage(
                any(), any(), anyList(), any(), any(), any()
        )).willReturn(List.of(setlog));
        given(petDisplayQueryService.getPetDisplaySummaries(List.of(130L)))
                .willReturn(Map.of(130L, petSummary(130L, 3L)));
        given(friendRelationshipQueryService.getRelationships(9L, List.of(130L)))
                .willReturn(Map.of(130L, FriendRelationship.FRIEND));
        given(setlogReactionRepository
                .findAllBySetlog_IdInAndReactorPet_Id(List.of(30L), 9L))
                .willReturn(List.of());
        given(mediaService.getPresignedDownloadUrls(anyList()))
                .willReturn(Map.of(230L, url("only")));

        SetlogListResponse result = service.getSetlogs(USER_ID, null, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().source())
                .isEqualTo(SetlogSource.USER);
        assertThat(result.items().getFirst().canInteract()).isTrue();
        assertThat(result.items().getFirst().authorPet().relationship())
                .isEqualTo(FriendRelationship.FRIEND);
        then(friendRelationshipQueryService).should()
                .getRelationships(9L, List.of(130L));
        then(setlogReactionRepository).should()
                .findAllBySetlog_IdInAndReactorPet_Id(List.of(30L), 9L);
    }

    @Test
    void ownUserSetlogCannotBeInteractedWith() {
        activeUser(true);
        given(activePetQueryService.requireActivePet(USER_ID))
                .willReturn(new ActivePetContext(
                        9L, USER_ID, "내개#ABCD", "내개", null, true
                ));
        Setlog setlog = setlog(30L, 130L, 230L, "2026-08-11T03:00:00Z");
        given(setlogRepository.findVisibleFeedFirstPage(
                any(), any(), anyList(), any(), any(), any()
        )).willReturn(List.of(setlog));
        given(petDisplayQueryService.getPetDisplaySummaries(List.of(130L)))
                .willReturn(Map.of(130L, petSummary(130L, USER_ID)));
        given(friendRelationshipQueryService.getRelationships(9L, List.of(130L)))
                .willReturn(Map.of());
        given(setlogReactionRepository
                .findAllBySetlog_IdInAndReactorPet_Id(List.of(30L), 9L))
                .willReturn(List.of());
        given(mediaService.getPresignedDownloadUrls(anyList()))
                .willReturn(Map.of(230L, url("own")));

        SetlogListResponse result = service.getSetlogs(USER_ID, null, 1);

        assertThat(result.items().getFirst().source())
                .isEqualTo(SetlogSource.USER);
        assertThat(result.items().getFirst().canInteract()).isFalse();
    }

    @Test
    void rejectsLimitOutsideSupportedRange() {
        activeUser(false);

        BusinessException tooSmall = assertThrows(
                BusinessException.class,
                () -> service.getSetlogs(USER_ID, null, 0)
        );
        BusinessException tooLarge = assertThrows(
                BusinessException.class,
                () -> service.getSetlogs(USER_ID, null, 101)
        );

        assertThat(tooSmall.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(tooLarge.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        then(setlogRepository).shouldHaveNoInteractions();
    }

    @Test
    void acceptsMaximumSizeAndRequestsOneExtraCandidate() {
        activeUser(false);
        given(setlogRepository.findVisibleFeedFirstPage(
                any(), any(), anyList(), any(), any(), any()
        )).willReturn(List.of());

        service.getSetlogs(USER_ID, null, 100);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(setlogRepository).should().findVisibleFeedFirstPage(
                any(), any(), anyList(), any(), any(), pageable.capture()
        );
        assertThat(pageable.getValue().getPageSize()).isEqualTo(101);
    }

    private void activeUser(boolean hasActivePet) {
        User user = mock(User.class);
        given(user.isActive()).willReturn(true);
        if (hasActivePet) {
            given(user.hasActivePet()).willReturn(true);
        }
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
    }

    private Setlog setlog(Long id, Long petId, Long mediaId, String createdAt) {
        Setlog setlog = mock(Setlog.class);
        Pet pet = mock(Pet.class);
        Media media = mock(Media.class);
        given(setlog.getId()).willReturn(id);
        given(setlog.getAuthorPet()).willReturn(pet);
        given(setlog.getMedia()).willReturn(media);
        given(setlog.getCreatedAt()).willReturn(Instant.parse(createdAt));
        given(pet.getId()).willReturn(petId);
        given(media.getId()).willReturn(mediaId);
        return setlog;
    }

    private PetDisplaySummary petSummary(Long petId, Long ownerId) {
        return new PetDisplaySummary(
                petId, ownerId, "반려견#" + petId, "반려견", null,
                true, PetStatus.ACTIVE, null
        );
    }

    private PresignedDownloadUrl url(String name) {
        return new PresignedDownloadUrl(
                "https://example.com/" + name + ".mp4",
                Instant.parse("2026-08-11T05:00:00Z")
        );
    }
}
