package itda.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import itda.common.exception.BusinessException;
import itda.map.dto.MapPlaceResponse;
import itda.map.dto.MapPlaceType;
import itda.map.repository.AnimalHospitalRepository;
import itda.map.repository.AnimalPharmacyRepository;
import itda.map.repository.NearbyMapPlaceRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MapPlaceServiceTest {

    @Mock AnimalHospitalRepository hospitalRepository;
    @Mock AnimalPharmacyRepository pharmacyRepository;

    private MapPlaceService service;

    @BeforeEach
    void setUp() {
        service = new MapPlaceService(hospitalRepository, pharmacyRepository);
    }

    @Test
    void findsHospitalsWithinDefaultThreeKilometerRadiusOrderedByRepository() {
        BigDecimal longitude = new BigDecimal("127.0276");
        BigDecimal latitude = new BigDecimal("37.4979");
        NearbyMapPlaceRow row = mock(NearbyMapPlaceRow.class);
        when(row.getPlaceId()).thenReturn(10L);
        when(row.getName()).thenReturn("튼튼동물병원");
        when(row.getLongitude()).thenReturn(new BigDecimal("127.0280"));
        when(row.getLatitude()).thenReturn(new BigDecimal("37.4980"));
        when(row.getDistanceMeters()).thenReturn(124.6);
        when(hospitalRepository.findNearby(longitude, latitude, 3_000, 500))
                .thenReturn(List.of(row));

        List<MapPlaceResponse> result = service.getNearbyPlaces(
                MapPlaceType.HOSPITAL, longitude, latitude, null);

        assertThat(result).singleElement().satisfies(place -> {
            assertThat(place.placeId()).isEqualTo(10L);
            assertThat(place.type()).isEqualTo(MapPlaceType.HOSPITAL);
            assertThat(place.distanceMeters()).isEqualTo(124.6);
        });
        verify(pharmacyRepository, never()).findNearby(
                longitude, latitude, 3_000, 500);
    }

    @Test
    void rejectsRadiusLargerThanThreeKilometers() {
        assertThatThrownBy(() -> service.getNearbyPlaces(
                MapPlaceType.PHARMACY,
                new BigDecimal("127.0276"),
                new BigDecimal("37.4979"),
                3_001
        )).isInstanceOf(BusinessException.class);
    }
}
