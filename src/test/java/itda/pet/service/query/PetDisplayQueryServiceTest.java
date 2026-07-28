package itda.pet.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.domain.Pet;
import itda.pet.domain.PetStatus;
import itda.pet.repository.PetRepository;
import itda.user.domain.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final Long SECOND_PET_ID = 3L;
    private static final Long THIRD_PET_ID = 4L;

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

    @Nested
    @DisplayName("Describe: 여러 Pet의 과거 이력 표시용 요약을 조회한다")
    class DescribeGetPetDisplaySummaries {

        @Nested
        @DisplayName("Context: Pet ID Collection이 null일 때")
        class ContextWithNullPetIds {

            @Test
            @DisplayName("It: VALIDATION_FAILED를 반환하고 Repository를 호출하지 않는다")
            void itRejectsWithoutRepositoryAccess() {
                assertErrorCode(
                        () -> service.getPetDisplaySummaries(null),
                        ErrorCode.VALIDATION_FAILED
                );

                then(petRepository).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: Pet ID Collection에 null이 포함될 때")
        class ContextWithNullPetId {

            @Test
            @DisplayName("It: VALIDATION_FAILED를 반환하고 Repository를 호출하지 않는다")
            void itRejectsWithoutRepositoryAccess() {
                List<Long> petIds = new ArrayList<>();
                petIds.add(PET_ID);
                petIds.add(null);

                assertErrorCode(
                        () -> service.getPetDisplaySummaries(petIds),
                        ErrorCode.VALIDATION_FAILED
                );

                then(petRepository).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: 빈 Pet ID Collection일 때")
        class ContextWithEmptyPetIds {

            @Test
            @DisplayName("It: 빈 Map을 반환하고 Repository를 호출하지 않는다")
            void itReturnsEmptyMapWithoutRepositoryAccess() {
                Map<Long, PetDisplaySummary> result =
                        service.getPetDisplaySummaries(List.of());

                assertThat(result).isEmpty();
                then(petRepository).shouldHaveNoInteractions();
            }
        }

        @Nested
        @DisplayName("Context: 동일한 Pet ID가 여러 번 요청될 때")
        class ContextWithDuplicatePetIds {

            @Test
            @DisplayName("It: 중복을 제거해 한 번의 일괄 조회로 요약을 반환한다")
            void itLoadsDistinctIdsOnce() {
                Pet first = pet(PetStatus.ACTIVE, null);
                Pet second = pet(
                        SECOND_PET_ID,
                        owner(OWNER_ID),
                        "초코#C9N4",
                        "초코",
                        PetStatus.SUSPENDED,
                        null
                );
                given(petRepository.findAllById(org.mockito.ArgumentMatchers.any()))
                        .willReturn(List.of(second, first));

                Map<Long, PetDisplaySummary> result =
                        service.getPetDisplaySummaries(
                                List.of(PET_ID, PET_ID, SECOND_PET_ID, SECOND_PET_ID)
                        );

                assertThat(result).hasSize(2);
                assertThat(result).containsKeys(PET_ID, SECOND_PET_ID);
                then(petRepository).should(times(1)).findAllById(
                        argThat(ids -> toIdSet(ids).equals(
                                Set.of(PET_ID, SECOND_PET_ID)
                        ))
                );
                then(petRepository).should(never()).findById(anyLong());
                then(petRepository).should(never()).findByIdForUpdate(anyLong());
            }
        }

        @Nested
        @DisplayName("Context: ACTIVE·SUSPENDED·DELETED Pet이 함께 존재할 때")
        class ContextWithAllDisplayableStatuses {

            @Test
            @DisplayName("It: 상태와 삭제 시각을 보존한 모든 요약을 반환한다")
            void itReturnsAllStatusesWithoutFiltering() {
                Instant deletedAt = Instant.parse("2026-07-28T00:00:00Z");
                Pet active = pet(PetStatus.ACTIVE, null);
                Pet suspended = pet(
                        SECOND_PET_ID,
                        owner(OWNER_ID),
                        "초코#C9N4",
                        "초코",
                        PetStatus.SUSPENDED,
                        null
                );
                Pet deleted = pet(
                        THIRD_PET_ID,
                        owner(OWNER_ID),
                        "보리#D2P5",
                        "보리",
                        PetStatus.DELETED,
                        deletedAt
                );
                given(petRepository.findAllById(org.mockito.ArgumentMatchers.any()))
                        .willReturn(List.of(deleted, active, suspended));

                Map<Long, PetDisplaySummary> result =
                        service.getPetDisplaySummaries(
                                List.of(PET_ID, SECOND_PET_ID, THIRD_PET_ID)
                        );

                assertThat(result).hasSize(3);
                assertSummary(
                        result.get(PET_ID),
                        PET_ID,
                        "몽이#B8M3",
                        "몽이",
                        PetStatus.ACTIVE,
                        null
                );
                assertSummary(
                        result.get(SECOND_PET_ID),
                        SECOND_PET_ID,
                        "초코#C9N4",
                        "초코",
                        PetStatus.SUSPENDED,
                        null
                );
                assertSummary(
                        result.get(THIRD_PET_ID),
                        THIRD_PET_ID,
                        "보리#D2P5",
                        "보리",
                        PetStatus.DELETED,
                        deletedAt
                );
            }
        }

        @Nested
        @DisplayName("Context: 요청한 Pet 중 하나가 물리적으로 존재하지 않을 때")
        class ContextWithMissingPet {

            @Test
            @DisplayName("It: 부분 결과를 반환하지 않고 PET_NOT_FOUND를 반환한다")
            void itRejectsWithoutPartialResult() {
                Pet existing = pet(PetStatus.ACTIVE, null);
                given(petRepository.findAllById(org.mockito.ArgumentMatchers.any()))
                        .willReturn(List.of(existing));

                assertErrorCode(
                        () -> service.getPetDisplaySummaries(
                                List.of(PET_ID, SECOND_PET_ID)
                        ),
                        ErrorCode.PET_NOT_FOUND
                );
            }
        }

        @Nested
        @DisplayName("Context: 모든 Pet이 정상 조회될 때")
        class ContextWithAllPetsPresent {

            @Test
            @DisplayName("It: 수정할 수 없는 Map을 반환한다")
            void itReturnsUnmodifiableMap() {
                Pet pet = pet(PetStatus.ACTIVE, null);
                given(petRepository.findAllById(org.mockito.ArgumentMatchers.any()))
                        .willReturn(List.of(pet));

                Map<Long, PetDisplaySummary> result =
                        service.getPetDisplaySummaries(List.of(PET_ID));

                assertThatThrownBy(() -> result.put(
                        999L,
                        new PetDisplaySummary(
                                999L,
                                OWNER_ID,
                                "추가#E3Q6",
                                "추가",
                                null,
                                false,
                                PetStatus.ACTIVE,
                                null
                        )
                )).isInstanceOf(UnsupportedOperationException.class);
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

    private void assertSummary(
            PetDisplaySummary summary,
            Long petId,
            String publicTag,
            String nickname,
            PetStatus status,
            Instant deletedAt
    ) {
        assertThat(summary.petId()).isEqualTo(petId);
        assertThat(summary.ownerUserId()).isEqualTo(OWNER_ID);
        assertThat(summary.publicTag()).isEqualTo(publicTag);
        assertThat(summary.nickname()).isEqualTo(nickname);
        assertThat(summary.profileUrl()).isNull();
        assertThat(summary.verified()).isFalse();
        assertThat(summary.status()).isEqualTo(status);
        assertThat(summary.deletedAt()).isEqualTo(deletedAt);
    }

    private Pet pet(PetStatus status, Instant deletedAt) {
        return pet(
                PET_ID,
                owner(OWNER_ID),
                "몽이#B8M3",
                "몽이",
                status,
                deletedAt
        );
    }

    private Pet pet(
            Long petId,
            User owner,
            String publicTag,
            String nickname,
            PetStatus status,
            Instant deletedAt
    ) {
        Pet pet = Pet.register(
                owner,
                publicTag,
                nickname,
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
        ReflectionTestUtils.setField(pet, "id", petId);
        ReflectionTestUtils.setField(pet, "status", status);
        ReflectionTestUtils.setField(pet, "deletedAt", deletedAt);
        return pet;
    }

    private User owner(Long ownerId) {
        User owner = User.register(
                "owner@example.com",
                "encoded",
                "보호자",
                "보호자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(owner, "id", ownerId);
        return owner;
    }

    private Set<Long> toIdSet(Iterable<Long> ids) {
        Set<Long> result = new LinkedHashSet<>();
        ids.forEach(result::add);
        return result;
    }

    private void assertErrorCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting(exception ->
                        ((BusinessException) exception).getErrorCode()
                )
                .isEqualTo(errorCode);
    }
}
