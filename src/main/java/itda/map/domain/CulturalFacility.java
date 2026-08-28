package itda.map.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "cultural_facility")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CulturalFacility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @Column(name = "geom", columnDefinition = "geometry(Point,4326)")
    private Point geom;

    @Column(name = "name", length = 254)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 254)
    private CulturalFacilityCategory category;

    @Column(name = "latitude", precision = 23, scale = 15)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 23, scale = 15)
    private BigDecimal longitude;

    @Column(name = "address", length = 254)
    private String address;

    @Column(name = "telephone", length = 254)
    private String telephone;

    @Column(name = "homepage", length = 254)
    private String homepage;

    @Column(name = "holiday", length = 254)
    private String holiday;

    @Column(name = "onhour", length = 254)
    private String operatingHours;

    @Column(name = "parklot", length = 254)
    private String parkingLot;

    @Column(name = "usage_fee", length = 254)
    private String usageFee;

    @Column(name = "pet_availa", length = 254)
    private String petAvailability;

    @Column(name = "pet_size", length = 254)
    private String petSize;

    @Column(name = "registrati", length = 254)
    private String registration;

    @Column(name = "descriptio", length = 254)
    private String description;

    @Column(name = "extra_fee", length = 254)
    private String extraFee;
}
