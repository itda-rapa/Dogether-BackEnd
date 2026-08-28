package itda.route.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** GeoJSON feature collection used by the saved-route popularity overlay. */
public record RouteHeatmapResponse(
        String type,
        List<Feature> features
) {

    public static RouteHeatmapResponse from(JsonNode source) {
        List<Feature> features = new ArrayList<>();
        JsonNode featureNodes = source == null ? null : source.path("features");
        if (featureNodes != null && featureNodes.isArray()) {
            for (JsonNode feature : featureNodes) {
                features.add(Feature.from(feature));
            }
        }
        return new RouteHeatmapResponse(
                source == null ? "FeatureCollection" : source.path("type").asText("FeatureCollection"),
                List.copyOf(features));
    }

    public record Feature(
            String type,
            Geometry geometry,
            Properties properties
    ) {
        private static Feature from(JsonNode source) {
            return new Feature(
                    source.path("type").asText("Feature"),
                    Geometry.from(source.path("geometry")),
                    new Properties(source.path("properties").path("usageCount").asLong()));
        }
    }

    public record Geometry(
            String type,
            List<List<BigDecimal>> coordinates
    ) {
        private static Geometry from(JsonNode source) {
            List<List<BigDecimal>> coordinates = new ArrayList<>();
            JsonNode coordinateNodes = source.path("coordinates");
            if (coordinateNodes.isArray()) {
                for (JsonNode coordinate : coordinateNodes) {
                    if (coordinate.isArray() && coordinate.size() >= 2) {
                        coordinates.add(List.of(
                                coordinate.get(0).decimalValue(),
                                coordinate.get(1).decimalValue()));
                    }
                }
            }
            return new Geometry(source.path("type").asText(), List.copyOf(coordinates));
        }
    }

    public record Properties(long usageCount) {
    }
}
