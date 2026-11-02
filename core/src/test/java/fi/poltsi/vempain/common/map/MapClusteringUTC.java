package fi.poltsi.vempain.common.map;

import org.junit.jupiter.api.Test;

import fi.poltsi.vempain.common.api.request.MapClusterRequest;

import static org.assertj.core.api.Assertions.assertThat;

class MapClusteringUTC {

    @Test
    void precisionGrowsWithTheZoomLevelUpToSingleResources() {
        assertThat(MapClustering.precisionForZoom(0)).isZero();
        assertThat(MapClustering.precisionForZoom(2)).isZero();
        assertThat(MapClustering.precisionForZoom(3)).isEqualTo(1);
        assertThat(MapClustering.precisionForZoom(5)).isEqualTo(1);
        assertThat(MapClustering.precisionForZoom(6)).isEqualTo(2);
        assertThat(MapClustering.precisionForZoom(9)).isEqualTo(2);
        assertThat(MapClustering.precisionForZoom(10)).isEqualTo(3);
        assertThat(MapClustering.precisionForZoom(12)).isEqualTo(3);
        assertThat(MapClustering.precisionForZoom(13)).isEqualTo(4);
        assertThat(MapClustering.precisionForZoom(15)).isEqualTo(4);
        assertThat(MapClustering.precisionForZoom(16)).isEqualTo(MapClustering.POINT_PRECISION);
        assertThat(MapClustering.precisionForZoom(22)).isEqualTo(MapClustering.POINT_PRECISION);
        assertThat(MapClustering.isPointPrecision(5)).isTrue();
        assertThat(MapClustering.isPointPrecision(4)).isFalse();
    }

    @Test
    void cellsRoundHalfAwayFromZeroLikePostgres() {
        assertThat(MapClustering.cell(60.17123, 2)).isEqualTo(60.17);
        assertThat(MapClustering.cell(60.175, 2)).isEqualTo(60.18);
        assertThat(MapClustering.cell(-24.945, 2)).isEqualTo(-24.95);
        assertThat(MapClustering.cell(60.5, 0)).isEqualTo(61.0);
        assertThat(MapClustering.cell(60.12345, 5)).isEqualTo(60.12345);
    }

    @Test
    void fileLimitIsDefaultedAndCapped() {
        assertThat(MapClustering.fileLimit(null)).isEqualTo(MapClustering.DEFAULT_FILE_LIMIT);
        assertThat(MapClustering.fileLimit(0)).isEqualTo(MapClustering.DEFAULT_FILE_LIMIT);
        assertThat(MapClustering.fileLimit(10)).isEqualTo(10);
        assertThat(MapClustering.fileLimit(10_000)).isEqualTo(MapClustering.MAX_FILE_LIMIT);
    }

    @Test
    void boundsAreOrderedAndClampedToTheWorld() {
        var request = MapClusterRequest.builder()
            .south(70.0)
            .north(-95.0)
            .west(200.0)
            .east(-10.0)
            .zoom(3)
            .build();

        var bounds = MapClustering.bounds(request);

        assertThat(bounds.south()).isEqualTo(-90.0);
        assertThat(bounds.north()).isEqualTo(70.0);
        assertThat(bounds.west()).isEqualTo(-10.0);
        assertThat(bounds.east()).isEqualTo(180.0);
    }
}
