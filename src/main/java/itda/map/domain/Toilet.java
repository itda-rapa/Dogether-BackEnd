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
@Table(name = "toilet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Toilet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
    private Point geom;

    @Column(name = "name", length = 254)
    private String name;

    @Column(name = "address", length = 254)
    private String address;

    @Column(name = "open_time", length = 254)
    private String openTime;

    @Column(name = "alarm_bell", length = 254)
    private String alarmBell;

    @Column(name = "cctv", length = 254)
    private String cctv;

    @Column(name = "diaper", length = 254)
    private String diaper;

    @Column(name = "update_at", length = 24)
    private String updatedAt;

    @Column(name = "longitude", precision = 23, scale = 15)
    private BigDecimal longitude;

    @Column(name = "latitude", precision = 23, scale = 15)
    private BigDecimal latitude;
}
