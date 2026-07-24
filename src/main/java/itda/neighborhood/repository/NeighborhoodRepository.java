package itda.neighborhood.repository;

import itda.neighborhood.domain.Neighborhood;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NeighborhoodRepository extends JpaRepository<Neighborhood, String> {

    boolean existsByCodeAndActiveTrue(String code);
}
