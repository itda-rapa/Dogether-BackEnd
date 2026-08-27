package itda.map.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.map.dto.MapPlaceResponse;
import itda.map.dto.MapPlaceType;
import itda.map.repository.AnimalHospitalRepository;
import itda.map.repository.AnimalPharmacyRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapPlaceService {

    private static final int MAX_RESULTS = 500;
    private static final int DEFAULT_RADIUS_METERS = 3_000;
    private static final int MAX_RADIUS_METERS = 3_000;

    private final AnimalHospitalRepository hospitalRepository;
    private final AnimalPharmacyRepository pharmacyRepository;

    public MapPlaceService(
            AnimalHospitalRepository hospitalRepository,
            AnimalPharmacyRepository pharmacyRepository
    ) {
        this.hospitalRepository = hospitalRepository;
        this.pharmacyRepository = pharmacyRepository;
    }

    @Transactional(readOnly = true)
    public List<MapPlaceResponse> getPlaces(
            MapPlaceType type,
            BigDecimal minLongitude,
            BigDecimal minLatitude,
            BigDecimal maxLongitude,
            BigDecimal maxLatitude
    ) {
        validateBounds(type, minLongitude, minLatitude, maxLongitude, maxLatitude);
        PageRequest limit = PageRequest.of(0, MAX_RESULTS);

        if (type == MapPlaceType.HOSPITAL) {
            return hospitalRepository.findInBounds(
                            minLongitude, minLatitude, maxLongitude, maxLatitude, limit)
                    .stream()
                    .map(MapPlaceResponse::from)
                    .toList();
        }

        return pharmacyRepository.findInBounds(
                        minLongitude, minLatitude, maxLongitude, maxLatitude, limit)
                .stream()
                .map(MapPlaceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MapPlaceResponse> getNearbyPlaces(
            MapPlaceType type,
            BigDecimal longitude,
            BigDecimal latitude,
            Integer requestedRadiusMeters
    ) {
        int radiusMeters = requestedRadiusMeters == null
                ? DEFAULT_RADIUS_METERS : requestedRadiusMeters;
        validateNearby(type, longitude, latitude, radiusMeters);

        if (type == MapPlaceType.HOSPITAL) {
            return hospitalRepository.findNearby(
                            longitude, latitude, radiusMeters, MAX_RESULTS)
                    .stream()
                    .map(place -> MapPlaceResponse.from(place, type))
                    .toList();
        }
        return pharmacyRepository.findNearby(
                        longitude, latitude, radiusMeters, MAX_RESULTS)
                .stream()
                .map(place -> MapPlaceResponse.from(place, type))
                .toList();
    }

    private void validateNearby(
            MapPlaceType type,
            BigDecimal longitude,
            BigDecimal latitude,
            int radiusMeters
    ) {
        if (type == null
                || longitude == null
                || latitude == null
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || radiusMeters < 1
                || radiusMeters > MAX_RADIUS_METERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateBounds(
            MapPlaceType type,
            BigDecimal minLongitude,
            BigDecimal minLatitude,
            BigDecimal maxLongitude,
            BigDecimal maxLatitude
    ) {
        if (type == null
                || minLongitude == null
                || minLatitude == null
                || maxLongitude == null
                || maxLatitude == null
                || minLongitude.compareTo(maxLongitude) >= 0
                || minLatitude.compareTo(maxLatitude) >= 0
                || minLongitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || maxLongitude.compareTo(BigDecimal.valueOf(180)) > 0
                || minLatitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || maxLatitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
