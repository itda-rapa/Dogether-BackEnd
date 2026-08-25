package itda.map.dto;

import itda.map.domain.AnimalHospital;
import itda.map.domain.AnimalPharmacy;
import java.math.BigDecimal;

public record MapPlaceResponse(
        Long placeId,
        MapPlaceType type,
        String name,
        String address,
        String phoneNumber,
        String status,
        BigDecimal longitude,
        BigDecimal latitude
) {

    public static MapPlaceResponse from(AnimalHospital hospital) {
        return new MapPlaceResponse(
                hospital.getId(),
                MapPlaceType.HOSPITAL,
                hospital.getStoreName(),
                hospital.getAddress(),
                phoneNumberOf(hospital.getPhoneNumber()),
                hospital.getStatus(),
                hospital.getLongitude(),
                hospital.getLatitude()
        );
    }

    public static MapPlaceResponse from(AnimalPharmacy pharmacy) {
        return new MapPlaceResponse(
                pharmacy.getId(),
                MapPlaceType.PHARMACY,
                pharmacy.getStoreName(),
                pharmacy.getAddress(),
                phoneNumberOf(pharmacy.getPhoneNumber()),
                pharmacy.getStatus(),
                pharmacy.getLongitude(),
                pharmacy.getLatitude()
        );
    }

    private static String phoneNumberOf(BigDecimal phoneNumber) {
        return phoneNumber == null ? null : phoneNumber.toPlainString();
    }
}
