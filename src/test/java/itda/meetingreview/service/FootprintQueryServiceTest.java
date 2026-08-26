package itda.meetingreview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import itda.meetingreview.domain.Footprint;
import itda.meetingreview.dto.FootprintListResponse;
import itda.meetingreview.repository.FootprintRepository;
import itda.pet.domain.PetStatus;
import itda.pet.service.query.ActivePetContext;
import itda.pet.service.query.ActivePetQueryService;
import itda.pet.service.query.PetDisplayQueryService;
import itda.pet.service.query.PetDisplaySummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 발자국 조회의 Pet 표시 공개 계약(#150). FootprintQueryService 는 PetRepository/Pet Entity 를
 * 직접 다루지 않고 {@link PetDisplayQueryService#getPetDisplaySummaries} 와
 * {@link PetDisplaySummary} 공개 필드만으로 응답을 조립한다. 생성자에 PetRepository 가 없으므로
 * 직접 접근 경로 자체가 없다.
 */
@ExtendWith(MockitoExtension.class)
class FootprintQueryServiceTest {

    private static final long USER_1 = 1L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final long MEETING_ID = 200L;
    private static final Instant CREATED_AT = Instant.parse("2026-08-20T09:10:00Z");
    private static final LocalDate EARNED_DATE = LocalDate.of(2026, 8, 20);

    @Mock
    private ActivePetQueryService activePetQueryService;
    @Mock
    private FootprintRepository footprintRepository;
    @Mock
    private PetDisplayQueryService petDisplayQueryService;

    private FootprintQueryService service;

    @BeforeEach
    void setUp() {
        service = new FootprintQueryService(
                activePetQueryService, footprintRepository, petDisplayQueryService);
        when(activePetQueryService.requireActivePet(USER_1))
                .thenReturn(new ActivePetContext(PET_1, USER_1, "pet#0011", "펫1", null, false));
    }

    @Test
    void assemblesCounterpartNicknameFromPetDisplaySummary() {
        Footprint footprint = footprint(81L, PET_2);
        when(footprintRepository.findReceivedFirstPage(PET_1, PageRequest.of(0, 21)))
                .thenReturn(List.of(footprint));
        PetDisplaySummary summary = new PetDisplaySummary(
                PET_2, 2L, "pet#0022", "초코", null, false, PetStatus.ACTIVE, null);
        when(petDisplayQueryService.getPetDisplaySummaries(Set.of(PET_2)))
                .thenReturn(Map.of(PET_2, summary));

        FootprintListResponse response = service.listMine(USER_1, null, null);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).counterpartPet().petId()).isEqualTo(PET_2);
        assertThat(response.items().get(0).counterpartPet().nickname()).isEqualTo("초코");
        assertThat(response.page().hasNext()).isFalse();
        verify(petDisplayQueryService).getPetDisplaySummaries(Set.of(PET_2));
    }

    @Test
    void emptyFootprintsDoNotCallPetDisplayService() {
        when(footprintRepository.findReceivedFirstPage(PET_1, PageRequest.of(0, 21)))
                .thenReturn(List.of());

        FootprintListResponse response = service.listMine(USER_1, null, null);

        assertThat(response.items()).isEmpty();
        verifyNoInteractions(petDisplayQueryService);
    }

    private Footprint footprint(long id, long counterpartPetId) {
        Footprint footprint = new Footprint(MEETING_ID, PET_1, counterpartPetId, EARNED_DATE);
        ReflectionTestUtils.setField(footprint, "id", id);
        ReflectionTestUtils.setField(footprint, "createdAt", CREATED_AT);
        return footprint;
    }
}
