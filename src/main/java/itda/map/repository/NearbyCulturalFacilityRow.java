package itda.map.repository;

import java.math.BigDecimal;

public interface NearbyCulturalFacilityRow {
    Integer getFacilityId();
    String getName();
    String getAddress();
    String getTelephone();
    String getHomepage();
    String getOperatingHours();
    BigDecimal getLongitude();
    BigDecimal getLatitude();
    Double getDistanceMeters();
}
