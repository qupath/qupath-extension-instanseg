package qupath.ext.instanseg.core;

import org.bytedeco.opencv.opencv_core.Mat;
import org.locationtech.jts.geom.Envelope;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.experimental.pixels.PixelProcessorUtils;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.GeometryTools;
import qupath.lib.scripting.QP;
import qupath.opencv.tools.OpenCVTools;

import java.util.ArrayList;
import java.util.List;

class OutputToObjectConverter implements OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> {

    private OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> converter = OpenCVProcessor.createDetectionConverter();

    @Override
    public List<PathObject> convertToObjects(Parameters params, Mat output) {
        List<PathObject> detections = new ArrayList<>();
        int channelCount = 0;
        for (var mat : OpenCVTools.splitChannels(output)) {
            List<PathObject> pathObjects = converter.convertToObjects(params, mat);
            if (output.channels() > 1) {
                PathClass pathClass = QP.getPathClass("Cell5", QP.makeRGB(90, 220, 90));
                if (channelCount == 0)
                    pathClass = QP.getPathClass("Nucleus3", QP.makeRGB(200, 70, 70));
                for (var p: pathObjects) {
                    p.setPathClass(pathClass);
                }
            }
            detections.addAll(pathObjects);
            channelCount++;
        }
        return detections;
    }

    static class PruneObjectOutputHandler<S, T, U> implements OutputHandler<S, T, U> {

        private OutputToObjectConverter converter;
        private final double boundaryThresholdPixels;

        PruneObjectOutputHandler(OutputToObjectConverter converter, double boundaryThresholdPixels) {
            this.converter = converter;
            this.boundaryThresholdPixels = boundaryThresholdPixels;
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
                var regionRequest = GeometryTools.createRectangle(
                        params.getRegionRequest().getX(), params.getRegionRequest().getY(),
                        params.getRegionRequest().getWidth(), params.getRegionRequest().getHeight());

                int maxX = params.getServer().getWidth();
                int maxY = params.getServer().getHeight();
                newObjects = newObjects.stream()
                        .filter(p -> !testIfTouching(p.getROI().getGeometry().getEnvelopeInternal(), regionRequest.getEnvelopeInternal(), boundaryThresholdPixels, maxX, maxY))
                        .toList();

                if (!newObjects.isEmpty()) {
                    // since we're using IoU to merge objects, we want to keep anything that is within the overall object bounding box
                    var parent = params.getParent().getROI();
                    newObjects = newObjects.stream()
                            .flatMap(p -> PixelProcessorUtils.maskObject(parent, p).stream())
                            .toList();
                }
                parentOrProxy.addChildObjects(newObjects);
                parentOrProxy.setLocked(true);
                return true;
            }
        }


        private boolean testIfTouching(Envelope det, Envelope ann, double pixelOverlapTolerance, int maxX, int maxY) {
            if (det.getMinX() < pixelOverlapTolerance || det.getMinY() < pixelOverlapTolerance) {
                return false;
            }
            if (maxX - det.getMaxX() < pixelOverlapTolerance || maxY - det.getMaxY() < pixelOverlapTolerance) {
                return false;
            }
            return Math.abs(ann.getMaxY() - det.getMaxY()) < pixelOverlapTolerance
                    || Math.abs(det.getMinY() - ann.getMinY()) < pixelOverlapTolerance
                    || Math.abs(ann.getMaxX() - det.getMaxX()) < pixelOverlapTolerance
                    || Math.abs(det.getMinX() - ann.getMinX()) < pixelOverlapTolerance;
        }
    }
}
