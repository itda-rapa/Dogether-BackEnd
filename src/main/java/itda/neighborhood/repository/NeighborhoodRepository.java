package itda.neighborhood.repository;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.neighborhood.domain.Neighborhood;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NeighborhoodRepository extends JpaRepository<Neighborhood, String> {

    Optional<Neighborhood> findByCode(String code);

    default Neighborhood findByCodeOrThrow(String code) {
        return findByCode(code).orElseThrow(
                () -> new BusinessException(ErrorCode.NEIGHBORHOOD_NOT_FOUND)
        );
    }

    boolean existsByCodeAndActiveTrue(String code);

    List<Neighborhood> findAllByActiveTrueOrderByCodeAsc();
}
