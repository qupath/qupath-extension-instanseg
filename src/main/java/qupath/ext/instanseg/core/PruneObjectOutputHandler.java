package qupath.ext.instanseg.core;

import org.locationtech.jts.geom.Envelope;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.experimental.pixels.PixelProcessorUtils;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.GeometryTools;

import java.util.List;

class PruneObjectOutputHandler<S, T, U> implements OutputHandler<S, T, U> {

    private final OutputToObjectConverter<S, T, U> converter;
    private final int boundaryThreshold;

    /**
     * An output handler that prunes the output, removing any objects that are
     * within a certain distance (in pixels) to the tile boundaries, leaving
     * all objects on the border of the image.
     * <p>
     * Relies on having a relatively large overlap between tiles.
     * <p>
     * Useful if you want to use for example IoU to merge objects between tiles,
     * where the general QuPath approach of merging objects with shared
     * boundaries won't work.
     * @param converter An output to object converter.
     * @param boundaryThreshold The size of the boundary, in pixels, to use for removing objects.
     *                          See {@link #doesntTouchBoundaries} for more details.
     */
    PruneObjectOutputHandler(OutputToObjectConverter<S, T, U> converter, int boundaryThreshold) {
        this.converter = converter;
        this.boundaryThreshold = boundaryThreshold;
    }

    @Override
    public boolean handleOutput(Parameters<S, T> params, U output) {
        if (output == null)
            return false;
        else {
            List<PathObject> newObjects = converter.convertToObjects(params, output);
            if (newObjects == null)
                return false;
            // If using a proxy object (eg tile),
            // we want to remove things touching the tile boundary,
            // then add the objects to the proxy rather than the parent
            var parentOrProxy = params.getParentOrProxy();
            parentOrProxy.clearChildObjects();

            // remove features within N pixels of the region request boundaries
            var bounds = GeometryTools.createRectangle(
                    params.getRegionRequest().getX(), params.getRegionRequest().getY(),
                    params.getRegionRequest().getWidth(), params.getRegionRequest().getHeight());

            int width = params.getServer().getWidth();
            int height = params.getServer().getHeight();

            newObjects = newObjects.parallelStream()
                    .filter(p -> doesntTouchBoundaries(p.getROI().getGeometry().getEnvelopeInternal(), bounds.getEnvelopeInternal(), boundaryThreshold, width, height))
                    .toList();

            if (!newObjects.isEmpty()) {
                // since we're using IoU to merge objects, we want to keep anything that is within the overall object bounding box
                var parent = params.getParent().getROI();
                newObjects = newObjects.parallelStream()
                        .flatMap(p -> PixelProcessorUtils.maskObject(parent, p).stream())
                        .toList();
            }
            parentOrProxy.addChildObjects(newObjects);
            parentOrProxy.setLocked(true);
            return true;
        }
    }


    /**
     * Tests if a detection is near the boundary of a parent region.
     * It first checks if the detection is on the edge of the overall image, in which case it should be kept,
     * unless it is at the edge of the image <b>and</b> the perpendicular edge of the parent region.
     * For example, on the left side of the image, but on the top/bottom edge of the parent region.
     * Then, it checks if the detection is on the boundary of the parent region.
     *
     * @param det            The detection object.
     * @param region         The region containing all detection objects.
     * @param boundaryPixels The size of the boundary, in pixels, to use for removing objects.
     * @param imageWidth     The width of the image, in pixels.
     * @param imageHeight    The height of the image, in pixels.
     * @return Whether the detection object should be removed, based on these criteria.
     */
    private boolean doesntTouchBoundaries(Envelope det, Envelope region, int boundaryPixels, int imageWidth, int imageHeight) {
        // keep any objects at the boundary of the annotation, except the stuff around region boundaries
        if (touchesLeftOfImage(det, boundaryPixels)) {
            if (touchesTopOfImage(det, boundaryPixels) || touchesBottomOfImage(det, imageHeight, boundaryPixels)) {
                return true;
            }
            if (!(touchesBottomOfRegion(det, region, boundaryPixels) || touchesTopOfRegion(det, region, boundaryPixels))) {
                return true;
            }
        }
        if (touchesTopOfImage(det, boundaryPixels)) {
            if (touchesLeftOfImage(det, boundaryPixels) || touchesRightOfImage(det, imageWidth, boundaryPixels)) {
                return true;
            }
            if (!(touchesLeftOfRegion(det, region, boundaryPixels) || touchesRightOfRegion(det, region, boundaryPixels))) {
                return true;
            }
        }

        if (touchesRightOfImage(det, imageWidth, boundaryPixels)) {
            if (touchesTopOfImage(det, boundaryPixels) || touchesBottomOfImage(det, imageHeight, boundaryPixels)) {
                return true;
            }
            if (!(touchesBottomOfRegion(det, region, boundaryPixels) || touchesTopOfRegion(det, region, boundaryPixels))) {
                return true;
            }
        }
        if (touchesBottomOfImage(det, imageHeight, boundaryPixels)) {
            if (touchesLeftOfImage(det, boundaryPixels) || touchesRightOfImage(det, imageWidth, boundaryPixels)) {
                return true;
            }
            if (!(touchesLeftOfRegion(det, region, boundaryPixels) || touchesRightOfRegion(det, region, boundaryPixels))) {
                return true;
            }
        }

        // remove any objects at other region boundaries
        return !(touchesLeftOfRegion(det, region, boundaryPixels)
                || touchesRightOfRegion(det, region, boundaryPixels)
                || touchesBottomOfRegion(det, region, boundaryPixels)
                || touchesTopOfRegion(det, region, boundaryPixels));
    }

    private static boolean touchesLeftOfImage(Envelope det, int boundary) {
        return det.getMinX() < boundary;
    }

    private static boolean touchesRightOfImage(Envelope det, int width, int boundary) {
        return width - det.getMaxX() < boundary;
    }

    private static boolean touchesTopOfImage(Envelope det, int boundary) {
        return det.getMinY() < boundary;
    }

    private static boolean touchesBottomOfImage(Envelope det, int height, int boundary) {
        return height - det.getMaxY() < boundary;
    }

    private static boolean touchesLeftOfRegion(Envelope det, Envelope region, int boundary) {
        return det.getMinX() - region.getMinX() < boundary;
    }

    private static boolean touchesRightOfRegion(Envelope det, Envelope region, int boundary) {
        return region.getMaxX() - det.getMaxX() < boundary;
    }

    private static boolean touchesTopOfRegion(Envelope det, Envelope region, int boundary) {
        return det.getMinY() - region.getMinY() < boundary;
    }

    private static boolean touchesBottomOfRegion(Envelope det, Envelope region, int boundary) {
        return region.getMaxY() - det.getMaxY() < boundary;
    }
}
