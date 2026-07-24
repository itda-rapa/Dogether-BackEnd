package itda.neighborhood.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "neighborhoods")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Neighborhood extends BaseEntity {

    @Id
    @Column(length = 20)
    private String code;

    @Column(name = "sido_name", nullable = false, length = 50)
    private String sidoName;

    @Column(name = "sigungu_name", length = 50)
    private String sigunguName;

    @Column(name = "eupmyeondong_name", length = 50)
    private String eupmyeondongName;

    @Column(nullable = false)
    private boolean active = true;
}
