package itda.map.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "water_fountain")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WaterFountain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
    private Point geom;

    @Column(name = "park_name", columnDefinition = "VARCHAR")
    private String parkName;

    @Column(name = "address", columnDefinition = "VARCHAR")
    private String address;

    @Column(name = "longitude")
    private BigDecimal longitude;

    @Column(name = "latitude")
    private BigDecimal latitude;
}
