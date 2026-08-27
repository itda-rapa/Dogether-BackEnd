package itda.chat.dto.response;

import java.util.List;

public record ChatMapMessageResponse(
        String category,
        List<MapFacilitySnapshot> facilities
) {
}
