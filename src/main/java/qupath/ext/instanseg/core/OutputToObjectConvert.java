package qupath.ext.instanseg.core;


import org.bytedeco.opencv.opencv_core.Mat;
import org.locationtech.jts.geom.Envelope;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;
import qupath.opencv.tools.OpenCVTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class OutputToObjectConvert implements OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> {

    private OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> converter = OpenCVProcessor.createDetectionConverter();

    @Override
    public List<PathObject> convertToObjects(Parameters params, Mat output) {
        List<PathObject> detections = new ArrayList<>();
        int channelCount = 0;
        for (var mat : OpenCVTools.splitChannels(output)) {
            List<PathObject> pathObjects = converter.convertToObjects(params, mat);
            if (output.channels() > 1) {
                if (channelCount == 1) continue;
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

        enum MaskMode {
            NONE,
            MASK_ONLY,
            MASK_AND_SPLIT
        }

        private OutputToObjectConverter converter;
        private boolean clearPreviousObjects;

        PruneObjectOutputHandler(OutputToObjectConverter converter, boolean clearPreviousObjects) {
            this.converter = converter;
            this.clearPreviousObjects = clearPreviousObjects;
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
                if (clearPreviousObjects)
                    parentOrProxy.clearChildObjects();
                var rr = GeometryTools.createRectangle(
                        params.getRegionRequest().getX(), params.getRegionRequest().getY(),
                        params.getRegionRequest().getWidth(), params.getRegionRequest().getHeight());

                QP.addObject(PathObjects.createAnnotationObject(GeometryTools.geometryToROI(rr, ImagePlane.getPlane(0, 0))));

                newObjects = newObjects.stream()
                        .filter(p -> !testIfTouching(p.getROI().getGeometry().getEnvelopeInternal(), rr.getEnvelopeInternal(), 30))
                        .toList();

                // usually pixelprocessor crops here,
                // but this means we can't later use an IoU merger...
                // if (!newObjects.isEmpty()) {
                //     // If we need to clip, then use the intersection of the 'real' parent and proxy
                //     var parent = params.getParent();
                //     var parentROI = intersection(parent.getROI(), parentOrProxy.getROI());
                //     newObjects = newObjects.stream()
                //             .flatMap(p -> PixelProcessorUtils.maskObject(parentROI, p).stream())
                //             .toList();
                // }
                parentOrProxy.addChildObjects(newObjects);
                parentOrProxy.setLocked(true);
                return true;
            }
        }

        private boolean testIfTouching(Envelope det, Envelope ann, double pixelOverlapTolerance) {
            return
                    Math.abs(ann.getMaxY() - det.getMaxY()) < pixelOverlapTolerance
                            || Math.abs(det.getMinY() - ann.getMinY()) < pixelOverlapTolerance
                            || Math.abs(ann.getMaxX() - det.getMaxX()) < pixelOverlapTolerance
                            || Math.abs(det.getMinX() - ann.getMinX()) < pixelOverlapTolerance;
        }

        private static ROI intersection(ROI roi1, ROI roi2) {
            if (Objects.equals(roi1, roi2))
                return roi1;
            else if (roi1 == null)
                return roi2;
            else if (roi2 == null)
                return roi1;
            else
                return RoiTools.intersection(roi1, roi2);
        }




    }
}
