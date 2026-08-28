package itda.node.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "network_node")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NetworkNode {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "network_node_id_generator")
    @SequenceGenerator(
            name = "network_node_id_generator",
            sequenceName = "network_node_id_seq",
            allocationSize = 1
    )
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(
            name = "geom",
            columnDefinition = "geometry(Point,4326)"
    )
    private Point geom;

    @Column(
            name = "angle",
            precision = 23,
            scale = 15
    )
    private BigDecimal angle;

    @Column(
            name = "path",
            length = 254
    )
    private String path;

    @Column(name = "node_id")
    private Long nodeId;

    @Column(
            name = "longitude",
            precision = 12,
            scale = 8
    )
    private BigDecimal longitude;

    @Column(
            name = "latitude",
            precision = 12,
            scale = 8
    )
    private BigDecimal latitude;
}
