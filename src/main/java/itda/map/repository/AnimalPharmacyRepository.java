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
}
