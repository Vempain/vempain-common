package fi.poltsi.vempain.common.metadata;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GPS position and place names read from a file's metadata by {@link MetadataJson#extractGpsData}. Coordinates are decimal degrees with the cardinal reference
 * kept separately, like the GPS tags they come from; every field is {@code null} when the file does not carry it.
 */
@Data
@NoArgsConstructor
public class GpsData {

    private BigDecimal latitude;
    private Character latitudeRef;
    private BigDecimal longitude;
    private Character longitudeRef;
    private Double altitude;
    private Double direction;
    private Integer satelliteCount;
    private String country;
    private String state;
    private String city;
    private String street;
    private String subLocation;

    /**
     * @return whether the file carried a position
     */
    public boolean hasCoordinates() {
        return latitude != null
               && longitude != null;
    }
}
