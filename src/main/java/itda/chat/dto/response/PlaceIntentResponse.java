package itda.chat.dto.response;

public record PlaceIntentResponse(
        Decision decision,
        PlaceType placeType,
        Long targetPetId
) {
    public enum Decision { SHOW, SUPPRESS }
    public enum PlaceType {
        HOSPITAL,
        PHARMACY,
        ART_CENTER,
        ART_GALLERY,
        BEAUTY,
        MUSEUM,
        SHOP,
        RESTAURANT,
        TOUR_SPOT,
        OUTSOURCE,
        CAFE,
        RENTAL_HOUSE,
        HOTEL
    }
}
