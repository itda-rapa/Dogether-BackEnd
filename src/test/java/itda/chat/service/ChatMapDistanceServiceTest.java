package itda.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import itda.chat.dto.response.MapFacilitySnapshot;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMapDistanceServiceTest {

    @Test
    void ranksFacilitiesByParticipantsAverageStraightLineDistance() {
        MapFacilitySnapshot nearBoth = facility(
                1, "중간 시설", 127.005, 37.5);
        MapFacilitySnapshot farFromBoth = facility(
                2, "먼 시설", 128.0, 38.0);
        List<ChatMapDistanceService.Coordinate> participants = List.of(
                coordinate(127.0, 37.5),
                coordinate(127.01, 37.5));

        List<MapFacilitySnapshot> ranked = ChatMapDistanceService.rankFacilities(
                List.of(farFromBoth, nearBoth), participants);

        assertThat(ranked).extracting(MapFacilitySnapshot::facilityId)
                .containsExactly(1, 2);
        assertThat(ranked).extracting(MapFacilitySnapshot::distanceRank)
                .containsExactly(1, 2);
        assertThat(ranked).allSatisfy(facility ->
                assertThat(facility.distanceParticipantCount()).isEqualTo(2));
        assertThat(ranked.getFirst().averageDistanceMeters()).isBetween(400.0, 500.0);
    }

    @Test
    void haversineReturnsCrowFliesDistanceInMeters() {
        double distance = ChatMapDistanceService.straightLineMeters(
                37.5665, 126.9780, 35.1796, 129.0756);

        assertThat(distance).isBetween(320_000.0, 330_000.0);
    }

    private MapFacilitySnapshot facility(
            int id,
            String name,
            double longitude,
            double latitude
    ) {
        return new MapFacilitySnapshot(
                id, name, null, null, null,
                BigDecimal.valueOf(longitude), BigDecimal.valueOf(latitude),
                null, null, null, null);
    }

    private ChatMapDistanceService.Coordinate coordinate(
            double longitude,
            double latitude
    ) {
        return new ChatMapDistanceService.Coordinate(
                BigDecimal.valueOf(longitude), BigDecimal.valueOf(latitude));
    }
}
