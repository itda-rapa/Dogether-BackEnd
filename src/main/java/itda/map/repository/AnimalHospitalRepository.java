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
}
