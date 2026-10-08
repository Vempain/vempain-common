package fi.poltsi.vempain.common.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(name = "LocationRequest", description = "GPS data request")
public class LocationRequest {

	@Schema(description = "Latitude in decimal degrees (5 decimals)", example = "60.17000")
	@NotNull
	@Digits(integer = 10, fraction = 5)
	private BigDecimal latitude;

	@Schema(description = "Latitude reference hemisphere", allowableValues = {"N", "S"}, example = "N")
	@NotNull
	private Character latitudeRef;

	@Schema(description = "Longitude in decimal degrees (5 decimals)", example = "24.93800")
	@NotNull
	@Digits(integer = 10, fraction = 5)
	private BigDecimal longitude;

	@Schema(description = "Longitude reference hemisphere", allowableValues = {"E", "W"}, example = "E")
	@NotNull
	private Character longitudeRef;

	@Schema(description = "Altitude in meters (can be negative below sea level)", example = "12.5")
	private Double altitude;

	@Schema(description = "Direction in degrees (0-360)", example = "275.3")
	@DecimalMin(value = "0.0")
	@DecimalMax(value = "360.0")
	private Double direction;

	@Schema(description = "Number of satellites used for the fix", example = "7")
	@PositiveOrZero
	private Integer satelliteCount;

	@Schema(description = "Country name", example = "Finland")
	@Size(max = 255)
	private String country;

	@Schema(description = "State or province", example = "Uusimaa")
	@Size(max = 255)
	private String state;

	@Schema(description = "City or locality", example = "Helsinki")
	@Size(max = 255)
	private String city;

	@Schema(description = "Street name", example = "Esplanadi")
	@Size(max = 255)
	private String street;

	@Schema(description = "Sub-location (neighborhood or district)", example = "Kaartinkaupunki")
	@Size(max = 255)
	private String subLocation;
}
