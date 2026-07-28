package itda.pet.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PetDisplayQueryServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long PET_ID = 2L;

    @Mock
    private PetRepository petRepository;

    private PetDisplayQueryService service;

    @BeforeEach
    void setUp() {
        service = new PetDisplayQueryService(petRepository);
    }

    @Nested
    @DisplayName("Describe: 과거 이력 표시용 Pet 요약을 조회한다")
    class DescribeGetPetDisplaySummary {

        @Nested
        @DisplayName("Context: Pet 행이 존재하지 않을 때")
        class ContextWithoutPet {

            @Test
            @DisplayName("It: PET_NOT_FOUND를 반환한다")
            void itReturnsPetNotFound() {
                given(petRepository.findById(PET_ID))
                        .willReturn(Optional.empty());

                assertThatThrownBy(() ->
                        service.getPetDisplaySummary(PET_ID)
                )
                        .isInstanceOf(BusinessException.class)
                        .extracting(exception ->
                                ((BusinessException) exception).getErrorCode()
                        )
                        .isEqualTo(ErrorCode.PET_NOT_FOUND);
            }
        }

        @Nested
        @DisplayName("Context: ACTIVE Pet이 존재할 때")
        class ContextWithActivePet {

            @Test
            @DisplayName("It: 소유권이나 Active 선택 여부 검사 없이 표시 정보를 반환한다")
            void itReturnsDisplayInformation() {
                Pet pet = pet(PetStatus.ACTIVE, null);
                given(petRepository.findById(PET_ID))
                        .willReturn(Optional.of(pet));

                PetDisplaySummary result =
                        service.getPetDisplaySummary(PET_ID);

                assertCommonSummary(result);
                assertThat(result.status()).isEqualTo(PetStatus.ACTIVE);
                assertThat(result.deletedAt()).isNull();
            }
        }

        @Nested
        @DisplayName("Context: SUSPENDED Pet이 존재할 때")
        class ContextWithSuspendedPet {

            @Test
            @DisplayName("It: ACTIVE 상태 필터 없이 요약을 반환한다")
            void itReturnsSummaryWithoutStatusFilter() {
                Pet pet = pet(PetStatus.SUSPENDED, null);
                given(petRepository.findById(PET_ID))
                        .willReturn(Optional.of(pet));

                PetDisplaySummary result =
                        service.getPetDisplaySummary(PET_ID);

                assertCommonSummary(result);
                assertThat(result.status()).isEqualTo(PetStatus.SUSPENDED);
                assertThat(result.deletedAt()).isNull();
            }
        }

        @Nested
        @DisplayName("Context: 삭제된 Pet을 표시용으로 조회할 때")
        class ContextWithDeletedPet {

            @Test
            @DisplayName("It: status와 deletedAt을 보존하여 반환한다")
            void itPreservesDeletedState() {
                Instant deletedAt =
                        Instant.parse("2026-07-28T00:00:00Z");
                Pet pet = pet(PetStatus.DELETED, deletedAt);
                given(petRepository.findById(PET_ID))
                        .willReturn(Optional.of(pet));

                PetDisplaySummary result =
                        service.getPetDisplaySummary(PET_ID);

                assertCommonSummary(result);
                assertThat(result.status()).isEqualTo(PetStatus.DELETED);
                assertThat(result.deletedAt()).isEqualTo(deletedAt);
            }
        }
    }

    private void assertCommonSummary(PetDisplaySummary summary) {
        assertThat(summary.petId()).isEqualTo(PET_ID);
        assertThat(summary.ownerUserId()).isEqualTo(OWNER_ID);
        assertThat(summary.publicTag()).isEqualTo("몽이#B8M3");
        assertThat(summary.nickname()).isEqualTo("몽이");
        assertThat(summary.profileUrl()).isNull();
        assertThat(summary.verified()).isFalse();
    }

    private Pet pet(PetStatus status, Instant deletedAt) {
        User owner = User.register(
                "owner@example.com",
                "encoded",
                "보호자",
                "보호자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(owner, "id", OWNER_ID);

        Pet pet = Pet.register(
                owner,
                "몽이#B8M3",
                "몽이",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        ReflectionTestUtils.setField(pet, "status", status);
        ReflectionTestUtils.setField(pet, "deletedAt", deletedAt);
        return pet;
    }
}
