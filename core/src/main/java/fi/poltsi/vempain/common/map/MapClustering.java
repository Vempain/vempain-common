package fi.poltsi.vempain.common.map;

import java.math.BigDecimal;
import java.math.RoundingMode;

import fi.poltsi.vempain.common.api.request.MapClusterRequest;

/**
 * The clustering rule of the map of GPS-tagged resources, shared by every backend that hosts {@code MapAPI}: points are grouped by their coordinates rounded to
 * a number of decimals that depends on the zoom level, so that a zoomed-out map answers few coarse clusters and a zoomed-in map single resources. The rounding
 * matches PostgreSQL's {@code ROUND(numeric, n)} (half away from zero), so a cell computed here finds the same resources the database grouped.
 */
public final class MapClustering {

    /** Coordinates are stored with five decimals; at this precision every cluster is one resource (or exact duplicates of a location). */
    public static final int POINT_PRECISION = 5;
    /** A cluster with at most this many resources is listed on mouseover; a larger one asks the user to zoom in. */
    public static final int MAX_LISTED_FILES = 50;
    /** The resources of a cluster are listed newest first and cut here, whatever limit the request asks for. */
    public static final int MAX_FILE_LIMIT = 200;
    /** Default number of resources of a cluster when the request names no limit. */
    public static final int DEFAULT_FILE_LIMIT = MAX_LISTED_FILES;
    /** At most this many clusters are answered for one viewport; a map of more cells is cut and marked truncated. */
    public static final int MAX_CLUSTERS = 2000;

    private MapClustering() {}

    /**
     * The number of decimals the coordinates are rounded to at a zoom level: 0 for the world view (cells of a degree, about 111 km), one more decimal every few
     * zoom levels, and {@link #POINT_PRECISION} from street level on, where every resource is its own marker.
     */
    public static int precisionForZoom(int zoom) {
        if (zoom <= 2) {
            return 0;
        }
        if (zoom <= 5) {
            return 1;
        }
        if (zoom <= 9) {
            return 2;
        }
        if (zoom <= 12) {
            return 3;
        }
        if (zoom <= 15) {
            return 4;
        }
        return POINT_PRECISION;
    }

    /** Whether clusters at this precision are single resources rather than groups */
    public static boolean isPointPrecision(int precision) {
        return precision >= POINT_PRECISION;
    }

    /** The cell a coordinate falls into at the precision, rounded like PostgreSQL's {@code ROUND(numeric, n)} */
    public static double cell(double coordinate, int precision) {
        return BigDecimal.valueOf(coordinate)
            .setScale(precision, RoundingMode.HALF_UP)
            .doubleValue();
    }

    /** The limit of a cluster's file listing: the request's limit capped at {@link #MAX_FILE_LIMIT}, {@link #DEFAULT_FILE_LIMIT} without one */
    public static int fileLimit(Integer requested) {
        if (requested == null
            || requested < 1) {
            return DEFAULT_FILE_LIMIT;
        }
        return Math.min(requested, MAX_FILE_LIMIT);
    }

    /**
     * The viewport of a cluster request as a normalised box: latitudes clamped to the poles and ordered south to north, longitudes clamped to the date line and
     * ordered west to east. A viewport wider than the world (a zoomed-out map shows it more than once) becomes the whole world.
     */
    public static Bounds bounds(MapClusterRequest request) {
        var south = clamp(request.getSouth(), -90, 90);
        var north = clamp(request.getNorth(), -90, 90);
        var west = clamp(request.getWest(), -180, 180);
        var east = clamp(request.getEast(), -180, 180);
        return new Bounds(Math.min(south, north), Math.max(south, north), Math.min(west, east), Math.max(west, east));
    }

    private static double clamp(Double value, double min, double max) {
        if (value == null) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** A normalised viewport: {@code south <= north}, {@code west <= east}, all in signed degrees */
    public record Bounds(double south, double north, double west, double east) {}
}
