package itda.map.repository;

import itda.map.domain.AnimalHospital;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnimalHospitalRepository extends JpaRepository<AnimalHospital, Long> {

    @Query("""
            select h from AnimalHospital h
            where h.longitude between :minLongitude and :maxLongitude
              and h.latitude between :minLatitude and :maxLatitude
            order by h.id asc
            """)
    List<AnimalHospital> findInBounds(
            @Param("minLongitude") BigDecimal minLongitude,
            @Param("minLatitude") BigDecimal minLatitude,
            @Param("maxLongitude") BigDecimal maxLongitude,
            @Param("maxLatitude") BigDecimal maxLatitude,
            Pageable pageable
    );

    @Query(value = """
            SELECT h.id AS placeId,
                   h.store_name AS name,
                   h.address AS address,
                   h.phone_number AS phoneNumber,
                   h.status AS status,
                   h.x_longitude AS longitude,
                   h.y_latitude AS latitude,
                   ST_Distance(
                       h.geom::geography,
                       ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                   ) AS distanceMeters
            FROM animal_hospital h
            WHERE h.geom IS NOT NULL
              AND ST_DWithin(
                  h.geom::geography,
                  ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                  :radiusMeters
              )
            ORDER BY distanceMeters ASC, h.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<NearbyMapPlaceRow> findNearby(
            @Param("longitude") BigDecimal longitude,
            @Param("latitude") BigDecimal latitude,
            @Param("radiusMeters") int radiusMeters,
            @Param("limit") int limit
    );
}
