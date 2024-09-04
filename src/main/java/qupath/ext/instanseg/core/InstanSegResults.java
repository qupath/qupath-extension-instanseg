package qupath.ext.instanseg.core;

/**
 * Record for storing a summary of an InstanSeg run.
 * @param nPixelsProcessed total number of pixels passed to the model for inference (including padding, excluding channels)
 * @param nTilesProcessed total number of tiles that were processed, including any that failed
 * @param nTilesFailed number of tiles that threw an exception during processing
 * @param nObjectsDetected number of objects detected in the image
 */
public record InstanSegResults(
        long nPixelsProcessed,
        int nTilesProcessed,
        int nTilesFailed,
        int nObjectsDetected,
        long processingTimeMillis) {
}
