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

    Optional<NetworkNode> findFirstByNodeId(Long nodeId);

    interface NearestNodeProjection {
        Long getNodeId();
        Double getLongitude();
        Double getLatitude();
    }

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

    @Query(value = """
    SELECT n.node_id AS nodeId,
           ST_X(n.geom) AS longitude,
           ST_Y(n.geom) AS latitude
    FROM network_node n
    WHERE n.geom IS NOT NULL
      AND (
          :role IN ('START', 'WAYPOINT', 'DESTINATION')
          AND EXISTS (
              SELECT 1 FROM network_link l
              WHERE (l.source = n.node_id OR l.target = n.node_id)
                AND CASE WHEN :activityType = 'CYCLE' THEN l.cycle_cost ELSE l.walk_cost_ END >= 0
          )
      )
    ORDER BY n.geom <-> ST_SetSRID(
        ST_MakePoint(:longitude, :latitude),
        4326
    )
    LIMIT 1
    """, nativeQuery = true)
    Optional<NearestNodeProjection> findNearestRouteNode(
            @Param("longitude") double longitude,
            @Param("latitude") double latitude,
            @Param("role") String role,
            @Param("activityType") String activityType
    );

    default NetworkNode findNearestNodeOrThrow(double longitude, double latitude) {
        return findNearestNode(longitude, latitude).orElseThrow(
                () -> new BusinessException(ErrorCode.NODE_NOT_FOUND)
        );
    }


    default NearestNodeProjection findNearestRouteNodeOrThrow(
            double longitude, double latitude, String role, String activityType) {
        return findNearestRouteNode(longitude, latitude, role, activityType).orElseThrow(
                () -> new BusinessException(ErrorCode.NODE_NOT_FOUND));
    }
}
