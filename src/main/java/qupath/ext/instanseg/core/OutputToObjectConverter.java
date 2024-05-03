package qupath.ext.instanseg.core;

import org.bytedeco.opencv.opencv_core.Mat;
import org.locationtech.jts.geom.Envelope;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.experimental.pixels.PixelProcessorUtils;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.tools.OpenCVTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

class OutputToObjectConverter implements OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> {

    private static final long seed = 1243;

    @Override
    public List<PathObject> convertToObjects(Parameters<Mat, Mat> params, Mat output) {
        if (output == null) {
            return List.of();
        }
        int nChannels = output.channels();
        if (nChannels < 1 || nChannels > 2)
            throw new IllegalArgumentException("Expected 1 or 2 channels, but found " + nChannels);

        List<Map<Number, ROI>> roiMaps = new ArrayList<>();
        for (var mat : OpenCVTools.splitChannels(output)) {
            var image = OpenCVTools.matToSimpleImage(mat, 0);
            roiMaps.add(
                    ContourTracing.createROIs(image, params.getRegionRequest(), 1, -1)
            );
        }
        if (roiMaps.size() == 1) {
            // One-channel detected, represent using detection objects
            return roiMaps.get(0).values().stream()
                    .map(PathObjects::createDetectionObject)
                    .collect(Collectors.toList());
        } else {
            // Two channels detected, represent using cell objects
            // We assume that the labels are matched - and we can't have a nucleus without a cell
            Map<Number, ROI> nucleusROIs = roiMaps.get(0);
            Map<Number, ROI> cellROIs = roiMaps.get(1);
            List<PathObject> cells = new ArrayList<>();
            var rng = new Random(seed);
            for (var entry : cellROIs.entrySet()) {
                var cell = entry.getValue();
                var nucleus = nucleusROIs.getOrDefault(entry.getKey(), null);
                var cellObject = PathObjects.createCellObject(cell, nucleus);
                cellObject.setColor(
                        rng.nextInt(255),
                        rng.nextInt(255),
                        rng.nextInt(255)
                );
                cells.add(cellObject);
            }
            return cells;
        }
    }

    static class PruneObjectOutputHandler<S, T, U> implements OutputHandler<S, T, U> {

        private final OutputToObjectConverter<S, T, U> converter;
        private final int boundaryThreshold;

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
         * @param det The detection object.
         * @param region The region containing all detection objects.
         * @param boundaryPixels The size of the boundary, in pixels, to use for removing object.
         * @param imageWidth The width of the image, in pixels.
         * @param imageHeight The height of the image, in pixels.
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
