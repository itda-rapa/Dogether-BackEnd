package itda.map.domain;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "animal_hospital")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnimalHospital {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(
            name = "geom",
            columnDefinition = "geometry(Point,4326)"
    )
    private Point geom;

    @Column(name = "approval_date")
    private LocalDate approvalDate;

    @Column(name = "status", length = 254)
    private String status;

    @Column(name = "store_name", length = 254)
    private String storeName;

    @Column(name = "address", length = 254)
    private String address;

    @Column(name = "phone_number")
    private BigDecimal phoneNumber;

    @Column(name = "x_longitude", precision = 23, scale = 15)
    private BigDecimal longitude;

    @Column(name = "y_latitude", precision = 23, scale = 15)
    private BigDecimal latitude;
}
