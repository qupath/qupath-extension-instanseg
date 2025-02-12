package qupath.ext.instanseg.core;

/**
 * Record for storing a summary of an InstanSeg run.
 * @param nPixelsProcessed total number of pixels passed to the model for inference (including padding, excluding channels)
 * @param nTilesProcessed total number of tiles that were processed, including any that failed
 * @param nTilesFailed number of tiles that threw an exception during processing
 * @param nObjectsDetected number of objects detected in the image
 * @param processingTimeMillis total time taken to process the image in milliseconds
 * @param wasInterrupted whether the processing was interrupted; if so, failed tiles are not necessary problematic
 */
public record InstanSegResults(
        long nPixelsProcessed,
        int nTilesProcessed,
        int nTilesFailed,
        int nObjectsDetected,
        long processingTimeMillis,
        boolean wasInterrupted) {

    private static final InstanSegResults EMPTY = new InstanSegResults(0, 0, 0, 0, 0, false);

    /**
     * Get an empty instance of InstanSegResults.
     * @return An empty instance with default values.
     */
    public static InstanSegResults emptyInstance() {
        return EMPTY;
    }

}
