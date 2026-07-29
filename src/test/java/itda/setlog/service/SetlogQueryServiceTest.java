package itda.setlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import itda.block.service.BlockRelationshipQueryService;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.service.MediaService;
import itda.media.service.MediaService.PresignedDownloadUrl;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import itda.setlog.domain.Setlog;
import itda.setlog.domain.SetlogStatus;
import itda.setlog.dto.SetlogResponse;
import itda.setlog.repository.SetlogReactionRepository;
import itda.setlog.repository.SetlogRepository;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SetlogQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long SETLOG_ID = 10L;
    private static final Long AUTHOR_PET_ID = 20L;
    private static final Long AUTHOR_USER_ID = 2L;

    @Mock
    private SetlogRepository setlogRepository;
    @Mock
    private SetlogReactionRepository setlogReactionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private PetDisplayQueryService petDisplayQueryService;
    @Mock
    private FriendRelationshipQueryService friendRelationshipQueryService;
    @Mock
    private BlockRelationshipQueryService blockRelationshipQueryService;
    @Mock
    private MediaService mediaService;

    private SetlogQueryService setlogQueryService;

    @BeforeEach
    void setUp() {
        setlogQueryService = new SetlogQueryService(
                setlogRepository,
                setlogReactionRepository,
                userRepository,
                activePetQueryService,
                petDisplayQueryService,
                friendRelationshipQueryService,
                blockRelationshipQueryService,
                mediaService
        );
    }

    @Test
    void l1UserCanReadSeedSetlogsWithoutInteractionContext() {
        User user = mock(User.class);
        Setlog setlog = setlog();
        Media media = mock(Media.class);
        given(setlog.getId()).willReturn(SETLOG_ID);
        given(setlog.getMedia()).willReturn(media);
        given(setlog.getCaption()).willReturn("같이 놀아요");
        given(setlog.getCuteCount()).willReturn(2);
        given(setlog.getLikeCount()).willReturn(1);
        given(setlog.getCreatedAt())
                .willReturn(Instant.parse("2026-07-30T01:00:00Z"));
        given(media.getId()).willReturn(30L);
        given(user.isActive()).willReturn(true);
        given(user.hasActivePet()).willReturn(false);
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.of(user));
        given(setlogRepository.findVisibleSeedSetlogs(
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
        )).willReturn(List.of(setlog));
        given(petDisplayQueryService.getPetDisplaySummaries(
                List.of(AUTHOR_PET_ID)
        )).willReturn(Map.of(
                AUTHOR_PET_ID,
                new PetDisplaySummary(
                        AUTHOR_PET_ID,
                        AUTHOR_USER_ID,
                        "몽이#A7K2",
                        "몽이",
                        null,
                        true,
                        PetStatus.ACTIVE,
                        null
                )
        ));
        given(blockRelationshipQueryService.existsBlockBetween(
                USER_ID,
                AUTHOR_USER_ID
        )).willReturn(false);
        given(mediaService.getPresignedDownloadUrl(30L))
                .willReturn(new PresignedDownloadUrl(
                        "https://example.com/seed.mp4",
                        Instant.parse("2026-07-30T01:10:00Z")
                ));

        List<SetlogResponse> result =
                setlogQueryService.getSeedSetlogs(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().setlogId()).isEqualTo(SETLOG_ID);
        assertThat(result.getFirst().myReactions()).isEmpty();
        assertThat(result.getFirst().canInteract()).isFalse();
        assertThat(result.getFirst().authorPet().relationship()).isNull();
        then(activePetQueryService).shouldHaveNoInteractions();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
        then(setlogReactionRepository).shouldHaveNoInteractions();
    }

    @Test
    void blockedAuthorIsHiddenBeforePresignedUrlIsIssued() {
        User user = mock(User.class);
        Setlog setlog = setlog();
        given(user.isActive()).willReturn(true);
        given(user.hasActivePet()).willReturn(false);
        given(userRepository.findById(USER_ID))
                .willReturn(Optional.of(user));
        given(setlogRepository.findVisibleSeedSetlogs(
                SetlogStatus.VISIBLE,
                List.of(MediaStatus.UPLOADED, MediaStatus.COMPLETED)
        )).willReturn(List.of(setlog));
        given(petDisplayQueryService.getPetDisplaySummaries(
                List.of(AUTHOR_PET_ID)
        )).willReturn(Map.of(
                AUTHOR_PET_ID,
                new PetDisplaySummary(
                        AUTHOR_PET_ID,
                        AUTHOR_USER_ID,
                        "몽이#A7K2",
                        "몽이",
                        null,
                        true,
                        PetStatus.ACTIVE,
                        null
                )
        ));
        given(blockRelationshipQueryService.existsBlockBetween(
                USER_ID,
                AUTHOR_USER_ID
        )).willReturn(true);

        List<SetlogResponse> result =
                setlogQueryService.getSeedSetlogs(USER_ID);

        assertThat(result).isEmpty();
        then(mediaService).shouldHaveNoInteractions();
    }

    private Setlog setlog() {
        Setlog setlog = mock(Setlog.class);
        Pet authorPet = mock(Pet.class);
        given(setlog.getAuthorPet()).willReturn(authorPet);
        given(authorPet.getId()).willReturn(AUTHOR_PET_ID);
        return setlog;
    }
}
