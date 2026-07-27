package itda.neighborhood.dto;

import itda.neighborhood.domain.Neighborhood;

public record NeighborhoodResponse(
        String code,
        String sidoName,
        String sigunguName,
        String eupmyeondongName
) {

    public static NeighborhoodResponse from(Neighborhood neighborhood) {
        return new NeighborhoodResponse(
                neighborhood.getCode(),
                neighborhood.getSidoName(),
                neighborhood.getSigunguName(),
                neighborhood.getEupmyeondongName()
        );
    }
}
