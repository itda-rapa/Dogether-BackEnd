package itda.petverification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import itda.media.service.MediaService;
import itda.pet.domain.Pet;
import itda.pet.repository.PetRepository;
import itda.pet.service.query.PetDisplayQueryService;
import itda.user.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PetVerificationBadgeBatchTest {

    @Mock private PetRepository petRepository;
    @Mock private MediaService mediaService;
    @Mock private PetVerificationBadgeService badgeService;

    private PetDisplayQueryService service;

    @BeforeEach
    void setUp() {
        service = new PetDisplayQueryService(petRepository, mediaService, badgeService);
    }

    @Test
    void displayBatchUsesOneVerificationBatchLookupInsteadOfPerPetLookups() {
        Pet first = pet(11L, "첫째#A1B2");
        Pet second = pet(12L, "둘째#C3D4");
        Instant verifiedAt = Instant.parse("2026-08-12T12:00:00Z");
        given(petRepository.findAllByIdWithOwnerAndProfileAsset(argThat(ids -> Set.copyOf(ids).equals(Set.of(11L, 12L)))))
                .willReturn(List.of(first, second));
        given(badgeService.verifiedAtByPetIds(argThat(ids -> Set.copyOf(ids).equals(Set.of(11L, 12L)))))
                .willReturn(Map.of(11L, verifiedAt));

        var display = service.getPetDisplaySummaries(List.of(11L, 12L, 11L));

        assertThat(display.get(11L).verified()).isTrue();
        assertThat(display.get(12L).verified()).isFalse();
        then(badgeService).should().verifiedAtByPetIds(argThat(ids -> Set.copyOf(ids).equals(Set.of(11L, 12L))));
        then(badgeService).should(never()).verifiedAt(11L);
        then(badgeService).should(never()).verifiedAt(12L);
    }

    private Pet pet(Long id, String publicTag) {
        User owner = User.register("owner" + id + "@example.test", "encoded", "Synthetic Owner",
                "owner#" + (id == 11 ? "A1B2" : "C3D4"), "4113111500");
        ReflectionTestUtils.setField(owner, "id", 7L);
        Pet pet = Pet.register(owner, publicTag, "Synthetic Pet", null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(pet, "id", id);
        return pet;
    }
}
