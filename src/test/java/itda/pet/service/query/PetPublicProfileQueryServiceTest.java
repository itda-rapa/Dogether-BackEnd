package itda.pet.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import itda.block.service.BlockRelationshipQueryService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.friend.domain.FriendRelationship;
import itda.friend.service.query.FriendRelationshipQueryService;
import itda.media.domain.Media;
import itda.media.domain.MediaStatus;
import itda.media.domain.MediaType;
import itda.media.service.MediaService;
import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.domain.PetStatus;
import itda.pet.dto.PetPublicProfileResponse;
import itda.pet.repository.PetRepository;
import itda.petverification.PetVerificationBadgeService;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PetPublicProfileQueryServiceTest {

    private static final Long VIEWER_ID = 1L;
    private static final Long OWNER_ID = 2L;
    private static final Long PET_ID = 10L;
    private static final Long ACTIVE_PET_ID = 20L;

    @Mock private PetRepository petRepository;
    @Mock private BlockRelationshipQueryService blockRelationshipQueryService;
    @Mock private ActivePetQueryService activePetQueryService;
    @Mock private FriendRelationshipQueryService friendRelationshipQueryService;
    @Mock private MediaService mediaService;
    @Mock private PetVerificationBadgeService badgeService;
    @Mock private PetHelpfulReceivedCountQueryService helpfulReceivedCountQueryService;

    private PetPublicProfileQueryService service;

    @BeforeEach
    void setUp() {
        service = new PetPublicProfileQueryService(petRepository, blockRelationshipQueryService,
                activePetQueryService, friendRelationshipQueryService, mediaService, badgeService,
                helpfulReceivedCountQueryService);
    }

    @Test
    void returnsExactlyTheFourteenPublicFieldsAndUsesLoadedMedia() {
        Pet pet = pet(OWNER_ID, List.of("친화적", "활발"));
        Media asset = media(51L);
        ReflectionTestUtils.setField(pet, "profileAsset", asset);
        givenVisiblePet(pet);
        given(activePetQueryService.findActivePet(VIEWER_ID)).willReturn(Optional.of(activePet()));
        given(friendRelationshipQueryService.getRelationships(ACTIVE_PET_ID, List.of(PET_ID)))
                .willReturn(Map.of(PET_ID, FriendRelationship.FRIEND));
        given(mediaService.getPresignedDownloadUrls(any()))
                .willReturn(Map.of(51L, new MediaService.PresignedDownloadUrl("https://cdn/profile", Instant.now())));
        given(badgeService.verifiedAt(PET_ID)).willReturn(Instant.parse("2026-08-01T00:00:00Z"));
        given(helpfulReceivedCountQueryService.countForPet(PET_ID)).willReturn(7L);

        PetPublicProfileResponse result = service.getPublicProfile(VIEWER_ID, PET_ID);

        assertThat(result).hasNoNullFieldsOrPropertiesExcept("profileUrl", "relationship");
        assertThat(result.petId()).isEqualTo(PET_ID);
        assertThat(result.publicTag()).isEqualTo("몽이#B8M3");
        assertThat(result.nickname()).isEqualTo("몽이");
        assertThat(result.profileUrl()).isEqualTo("https://cdn/profile");
        assertThat(result.verified()).isTrue();
        assertThat(result.breedName()).isEqualTo("말티즈");
        assertThat(result.sex()).isEqualTo(PetSex.FEMALE);
        assertThat(result.neutered()).isTrue();
        assertThat(result.birthDate()).isEqualTo(LocalDate.of(2020, 1, 2));
        assertThat(result.sizeCode()).isEqualTo(PetSizeCode.SMALL);
        assertThat(result.bio()).isEqualTo("사람을 좋아해요.");
        assertThat(result.personalityTags()).containsExactly("친화적", "활발");
        assertThat(result.helpfulReceivedCount()).isEqualTo(7L);
        assertThat(result.relationship()).isEqualTo(FriendRelationship.FRIEND);
        assertThat(recordProperties()).containsExactlyInAnyOrder(
                "petId", "publicTag", "nickname", "profileUrl", "verified", "breedName", "sex",
                "neutered", "birthDate", "sizeCode", "bio", "personalityTags", "helpfulReceivedCount",
                "relationship");
        then(mediaService).should().getPresignedDownloadUrls(argThat(items -> items.size() == 1
                && items.iterator().next().getId().equals(51L)));
        then(mediaService).should(never()).getPresignedDownloadUrl(anyLong());
        then(mediaService).should(never()).getPresignedUrl(anyLong());
    }

    @Test
    void nullTagsBecomeEmptyAndNoAssetDoesNotSignWhileFalseVerificationAndZeroHelpfulAreReturned() {
        Pet pet = pet(OWNER_ID, null);
        givenVisiblePet(pet);
        given(activePetQueryService.findActivePet(VIEWER_ID)).willReturn(Optional.of(activePet()));
        given(friendRelationshipQueryService.getRelationships(ACTIVE_PET_ID, List.of(PET_ID)))
                .willReturn(Map.of(PET_ID, FriendRelationship.NONE));
        given(badgeService.verifiedAt(PET_ID)).willReturn(null);
        given(helpfulReceivedCountQueryService.countForPet(PET_ID)).willReturn(0L);

        PetPublicProfileResponse result = service.getPublicProfile(VIEWER_ID, PET_ID);

        assertThat(result.personalityTags()).isEmpty();
        assertThat(result.profileUrl()).isNull();
        assertThat(result.verified()).isFalse();
        assertThat(result.helpfulReceivedCount()).isZero();
        assertThat(result.relationship()).isEqualTo(FriendRelationship.NONE);
        then(mediaService).shouldHaveNoInteractions();
    }

    @Test
    void mapsEveryRelationshipAndDefaultsMissingTargetToNone() {
        for (FriendRelationship relationship : List.of(FriendRelationship.NONE,
                FriendRelationship.REQUEST_SENT, FriendRelationship.REQUEST_RECEIVED,
                FriendRelationship.FRIEND)) {
            Pet pet = pet(OWNER_ID, List.of());
            givenVisiblePet(pet);
            given(activePetQueryService.findActivePet(VIEWER_ID)).willReturn(Optional.of(activePet()));
            given(friendRelationshipQueryService.getRelationships(ACTIVE_PET_ID, List.of(PET_ID)))
                    .willReturn(Map.of(PET_ID, relationship));
            PetPublicProfileResponse result = service.getPublicProfile(VIEWER_ID, PET_ID);
            assertThat(result.relationship()).isEqualTo(relationship);
        }
        Pet pet = pet(OWNER_ID, List.of());
        givenVisiblePet(pet);
        given(activePetQueryService.findActivePet(VIEWER_ID)).willReturn(Optional.of(activePet()));
        given(friendRelationshipQueryService.getRelationships(ACTIVE_PET_ID, List.of(PET_ID)))
                .willReturn(Map.of());

        assertThat(service.getPublicProfile(VIEWER_ID, PET_ID).relationship())
                .isEqualTo(FriendRelationship.NONE);
    }

    @Test
    void selfSkipsActivePetAndFriendRelationshipAndReturnsNullRelationship() {
        Pet pet = pet(VIEWER_ID, List.of());
        givenVisiblePet(pet);

        PetPublicProfileResponse result = service.getPublicProfile(VIEWER_ID, PET_ID);

        assertThat(result.relationship()).isNull();
        then(activePetQueryService).shouldHaveNoInteractions();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
    }

    @Test
    void viewerWithoutActivePetSkipsFriendRelationshipAndReturnsNullRelationship() {
        Pet pet = pet(OWNER_ID, List.of());
        givenVisiblePet(pet);
        given(activePetQueryService.findActivePet(VIEWER_ID)).willReturn(Optional.empty());

        assertThat(service.getPublicProfile(VIEWER_ID, PET_ID).relationship()).isNull();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
    }

    @Test
    void missingVisiblePetReturnsPetNotFoundAndShortCircuitsAllOtherQueries() {
        given(petRepository.findPublicProfileById(PET_ID, PetStatus.ACTIVE, AccountStatus.ACTIVE))
                .willReturn(Optional.empty());

        assertPetNotFound(() -> service.getPublicProfile(VIEWER_ID, PET_ID));

        then(blockRelationshipQueryService).shouldHaveNoInteractions();
        then(activePetQueryService).shouldHaveNoInteractions();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
        then(mediaService).shouldHaveNoInteractions();
        then(badgeService).shouldHaveNoInteractions();
        then(helpfulReceivedCountQueryService).shouldHaveNoInteractions();
    }

    @Test
    void bilateralBlockReturnsPetNotFoundAndShortCircuitsPostVisibilityQueries() {
        Pet pet = pet(OWNER_ID, List.of());
        given(petRepository.findPublicProfileById(PET_ID, PetStatus.ACTIVE, AccountStatus.ACTIVE))
                .willReturn(Optional.of(pet));
        given(blockRelationshipQueryService.existsBlockBetween(VIEWER_ID, OWNER_ID)).willReturn(true);

        assertPetNotFound(() -> service.getPublicProfile(VIEWER_ID, PET_ID));

        InOrder order = inOrder(petRepository, blockRelationshipQueryService);
        order.verify(petRepository).findPublicProfileById(PET_ID, PetStatus.ACTIVE, AccountStatus.ACTIVE);
        order.verify(blockRelationshipQueryService).existsBlockBetween(VIEWER_ID, OWNER_ID);
        then(activePetQueryService).shouldHaveNoInteractions();
        then(friendRelationshipQueryService).shouldHaveNoInteractions();
        then(mediaService).shouldHaveNoInteractions();
        then(badgeService).shouldHaveNoInteractions();
        then(helpfulReceivedCountQueryService).shouldHaveNoInteractions();
    }

    private void givenVisiblePet(Pet pet) {
        given(petRepository.findPublicProfileById(PET_ID, PetStatus.ACTIVE, AccountStatus.ACTIVE))
                .willReturn(Optional.of(pet));
        given(blockRelationshipQueryService.existsBlockBetween(VIEWER_ID, pet.getOwner().getId()))
                .willReturn(false);
    }

    private ActivePetContext activePet() {
        return new ActivePetContext(ACTIVE_PET_ID, VIEWER_ID, "나#A1B2", "나", null, false);
    }

    private Pet pet(Long ownerId, List<String> personalityTags) {
        User owner = User.register("owner@example.com", "encoded", "보호자", "보호자#A7K2", "4113111500");
        ReflectionTestUtils.setField(owner, "id", ownerId);
        Pet pet = Pet.register(owner, "몽이#B8M3", "몽이", "말티즈", PetSex.FEMALE, true,
                LocalDate.of(2020, 1, 2), new BigDecimal("3.40"), PetSizeCode.SMALL,
                "사람을 좋아해요.", personalityTags, "닭고기 알레르기");
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        return pet;
    }

    private Media media(Long id) {
        Media media = new Media(MediaType.IMAGE, "pets/profile.jpg", OWNER_ID, 1L);
        ReflectionTestUtils.setField(media, "id", id);
        ReflectionTestUtils.setField(media, "status", MediaStatus.UPLOADED);
        return media;
    }

    private Set<String> recordProperties() {
        return java.util.Arrays.stream(PetPublicProfileResponse.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
    }

    private void assertPetNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PET_NOT_FOUND);
    }
}
