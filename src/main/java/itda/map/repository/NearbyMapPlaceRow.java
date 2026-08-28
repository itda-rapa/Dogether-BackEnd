package itda.map.repository;

import java.math.BigDecimal;

public interface NearbyMapPlaceRow {

    Long getPlaceId();

    String getName();

    String getAddress();

    BigDecimal getPhoneNumber();

    String getStatus();

    BigDecimal getLongitude();

    BigDecimal getLatitude();

    Double getDistanceMeters();
}
