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
@Table(name = "poopbag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PoopBag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
    private Point geom;

    @Column(name = "dong_name", length = 254)
    private String dongName;

    @Column(name = "latitude", precision = 23, scale = 15)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 23, scale = 15)
    private BigDecimal longitude;

    @Column(name = "details", length = 254)
    private String details;
}
