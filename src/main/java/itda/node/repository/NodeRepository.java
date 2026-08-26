package itda.node.repository;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.node.domain.NetworkNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NodeRepository extends JpaRepository<NetworkNode, Long> {

    @Query(value = """
    SELECT *
    FROM network_node n
    WHERE n.geom IS NOT NULL
    ORDER BY n.geom <-> ST_SetSRID(
        ST_MakePoint(:longitude, :latitude),
        4326
    )
    LIMIT 1
    """, nativeQuery = true)
    Optional<NetworkNode> findNearestNode(
            @Param("longitude") double longitude,
            @Param("latitude") double latitude
    );

    default NetworkNode findNearestNodeOrThrow(double longitude, double latitude) {
        return findNearestNode(longitude, latitude).orElseThrow(
                () -> new BusinessException(ErrorCode.NODE_NOT_FOUND)
        );
    }
}
