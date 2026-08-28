package itda.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.common.exception.BusinessException;
import itda.map.domain.CulturalFacilityCategory;
import itda.map.repository.CulturalFacilityRepository;
import itda.map.repository.NearbyCulturalFacilityRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CulturalFacilityServiceTest {

    private final CulturalFacilityRepository repository = mock(CulturalFacilityRepository.class);
    private final CulturalFacilityService service = new CulturalFacilityService(repository);

    @Test
    void returnsNearestFiveForCategory() {
        NearbyCulturalFacilityRow row = mock(NearbyCulturalFacilityRow.class);
        when(row.getFacilityId()).thenReturn(7);
        when(row.getName()).thenReturn("반려 카페");
        when(repository.findNearest("CAFE", BigDecimal.valueOf(127), BigDecimal.valueOf(37.5), 5))
                .thenReturn(List.of(row));

        var result = service.findNearest(
                CulturalFacilityCategory.CAFE, BigDecimal.valueOf(127), BigDecimal.valueOf(37.5));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().category()).isEqualTo(CulturalFacilityCategory.CAFE);
        assertThat(result.getFirst().name()).isEqualTo("반려 카페");
        verify(repository).findNearest("CAFE", BigDecimal.valueOf(127), BigDecimal.valueOf(37.5), 5);
    }

    @Test
    void rejectsInvalidCoordinates() {
        assertThatThrownBy(() -> service.findNearest(
                CulturalFacilityCategory.HOTEL, BigDecimal.valueOf(181), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
    }
}
