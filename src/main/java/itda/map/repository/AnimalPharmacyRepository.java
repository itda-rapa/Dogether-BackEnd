package itda.map.repository;

import itda.map.domain.AnimalPharmacy;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnimalPharmacyRepository extends JpaRepository<AnimalPharmacy, Long> {

    @Query("""
            select p from AnimalPharmacy p
            where p.longitude between :minLongitude and :maxLongitude
              and p.latitude between :minLatitude and :maxLatitude
            order by p.id asc
            """)
    List<AnimalPharmacy> findInBounds(
            @Param("minLongitude") BigDecimal minLongitude,
            @Param("minLatitude") BigDecimal minLatitude,
            @Param("maxLongitude") BigDecimal maxLongitude,
            @Param("maxLatitude") BigDecimal maxLatitude,
            Pageable pageable
    );

    @Query(value = """
            SELECT p.id AS placeId,
                   p.store_name AS name,
                   p.address AS address,
                   p.phone_number AS phoneNumber,
                   p.status AS status,
                   p.x_longitude AS longitude,
                   p.y_latitude AS latitude,
                   ST_Distance(
                       p.geom::geography,
                       ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
                   ) AS distanceMeters
            FROM animal_pharmacy p
            WHERE p.geom IS NOT NULL
              AND ST_DWithin(
                  p.geom::geography,
                  ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                  :radiusMeters
              )
            ORDER BY distanceMeters ASC, p.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<NearbyMapPlaceRow> findNearby(
            @Param("longitude") BigDecimal longitude,
            @Param("latitude") BigDecimal latitude,
            @Param("radiusMeters") int radiusMeters,
            @Param("limit") int limit
    );
}
