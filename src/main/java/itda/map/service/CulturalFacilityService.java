package itda.map.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.map.domain.CulturalFacilityCategory;
import itda.map.dto.CulturalFacilityResponse;
import itda.map.repository.CulturalFacilityRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CulturalFacilityService {

    private static final int NEAREST_LIMIT = 5;
    private final CulturalFacilityRepository repository;

    public CulturalFacilityService(CulturalFacilityRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<CulturalFacilityResponse> findNearest(
            CulturalFacilityCategory category,
            BigDecimal longitude,
            BigDecimal latitude
    ) {
        validate(category, longitude, latitude);
        return repository.findNearest(category.name(), longitude, latitude, NEAREST_LIMIT)
                .stream()
                .map(row -> CulturalFacilityResponse.from(row, category))
                .toList();
    }

    private void validate(
            CulturalFacilityCategory category,
            BigDecimal longitude,
            BigDecimal latitude
    ) {
        if (category == null
                || longitude == null
                || latitude == null
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
