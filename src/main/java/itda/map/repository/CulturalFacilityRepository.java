package itda.map.repository;

import itda.map.domain.CulturalFacility;
import itda.map.domain.CulturalFacilityCategory;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CulturalFacilityRepository extends JpaRepository<CulturalFacility, Integer> {

    @Query(value = """
            SELECT f.id AS facilityId,
                   f.name AS name,
                   f.address AS address,
                   f.telephone AS telephone,
                   f.homepage AS homepage,
                   f.onhour AS operatingHours,
                   f.longitude AS longitude,
                   f.latitude AS latitude,
                   ST_Distance(
                       f.geom::geography,
                       ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                   ) AS distanceMeters
            FROM cultural_facility f
            WHERE f.category = CAST(:category AS text)
              AND f.geom IS NOT NULL
            ORDER BY f.geom::geography <->
                     ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                     f.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<NearbyCulturalFacilityRow> findNearest(
            @Param("category") String category,
            @Param("longitude") BigDecimal longitude,
            @Param("latitude") BigDecimal latitude,
            @Param("limit") int limit
    );
}
