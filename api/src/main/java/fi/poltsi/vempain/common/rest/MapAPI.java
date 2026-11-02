package fi.poltsi.vempain.common.rest;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import fi.poltsi.vempain.common.api.request.MapClusterFilesRequest;
import fi.poltsi.vempain.common.api.request.MapClusterRequest;
import fi.poltsi.vempain.common.api.response.MapClusterResponse;
import fi.poltsi.vempain.common.api.response.MapFileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The map of GPS-tagged resources, hosted by every backend that keeps files with coordinates (the file backend for the original files, the admin backend for
 * the published site files). The backend clusters the points: it rounds the coordinates to the number of decimals that fits the requested zoom level and
 * answers one cluster per cell, so that a map of very many resources stays small; the resources of one cluster are listed on request. Only the resources the
 * caller may read are counted and listed.
 */
@Tag(name = "MapAPI", description = "Clustered map of the GPS-tagged resources the caller may read")
public interface MapAPI {

    String BASE_PATH = "/map";

    @Operation(summary = "Clusters of the GPS-tagged resources in a viewport",
            description = "Groups the readable resources inside the bounds by their coordinates rounded to the precision of the zoom level (0 decimals at "
                    + "world level, 5 decimals, i.e. single resources, at street level) and answers one cluster per cell with its centroid and count; "
                    + "a cluster of one resource names it. The optional filters (file type, time taken, tags, file name text) narrow the resources.",
            tags = "MapAPI")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "The clusters",
                content = { @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = MapClusterResponse.class)) }),
        @ApiResponse(responseCode = "400", description = "Invalid bounds, zoom or filter", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content) })
    @SecurityRequirement(name = "Bearer Authentication")
    @PostMapping(value = BASE_PATH + "/clusters", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MapClusterResponse> getClusters(@Valid @RequestBody MapClusterRequest request);

    @Operation(summary = "Resources of one cluster",
            description = "The readable resources whose coordinates round to the given cell at the given precision, newest first, cut at the limit "
                    + "(at most the backend's listing limit), with the same filters the clusters were asked with. Meant for the mouseover list of a cluster "
                    + "that is small enough to list; larger clusters are zoomed into instead.",
            tags = "MapAPI")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "The resources of the cluster",
                content = {
                    @Content(array = @ArraySchema(schema = @Schema(implementation = MapFileResponse.class)), mediaType = MediaType.APPLICATION_JSON_VALUE) }),
        @ApiResponse(responseCode = "400", description = "Invalid cell, precision or filter", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized access", content = @Content) })
    @SecurityRequirement(name = "Bearer Authentication")
    @PostMapping(value = BASE_PATH + "/files", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<MapFileResponse>> getClusterFiles(@Valid @RequestBody MapClusterFilesRequest request);
}
