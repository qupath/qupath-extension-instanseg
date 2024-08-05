package qupath.ext.instanseg.core;

import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.common.ColorTools;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.tools.OpenCVTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

class InstansegOutputToObjectConverter implements OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> {
    private static final Logger logger = LoggerFactory.getLogger(InstansegOutputToObjectConverter.class);

    private static final long seed = 1243;
    private final List<Class<? extends PathObject>> classes;

    InstansegOutputToObjectConverter(List<Class<? extends PathObject>> outputClasses) {
        this.classes = outputClasses;
    }

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
        var rng = new Random(seed);

        BiFunction<ROI, ROI, PathObject> function;
        if (classes.size() == 1) {
            // if of length 1, can be
            // cellObject (with or without nucleus), annotations, detections
            if (classes.get(0) == PathAnnotationObject.class) {
                function = createObjectsFun(PathObjects::createAnnotationObject, PathObjects::createAnnotationObject, rng);
            } else if (classes.get(0) == PathDetectionObject.class) {
                function = createObjectsFun(PathObjects::createDetectionObject, PathObjects::createDetectionObject, rng);
            } else if (classes.get(0) == PathCellObject.class) {
                function = createCellFun(rng);
            } else {
                function = createCellFun(rng);
                logger.warn("Unknown output {}, defaulting to cells", classes.get(0));
            }
        } else {
            // if of length 2, then can be:
            // detection <- annotation, annotation <- annotation, detection <- detection
            assert classes.size() == 2;
            if (classes.get(0) == PathDetectionObject.class && classes.get(1) == PathAnnotationObject.class) {
                function = createObjectsFun(PathObjects::createDetectionObject, PathObjects::createAnnotationObject, rng);
            } else if (classes.get(0) == PathAnnotationObject.class && classes.get(1) == PathAnnotationObject.class) {
                function = createObjectsFun(PathObjects::createAnnotationObject, PathObjects::createAnnotationObject, rng);
            } else if (classes.get(0) == PathDetectionObject.class && classes.get(1) == PathDetectionObject.class) {
                function = createObjectsFun(PathObjects::createDetectionObject, PathObjects::createDetectionObject, rng);
            } else {
                logger.warn("Unknown combination of outputs {} <- {}, defaulting to cells", classes.get(0), classes.get(1));
                function = createCellFun(rng);
            }
        }

        if (roiMaps.size() == 1) {
            // One-channel detected, represent using detection objects
            return roiMaps.get(0).values().stream()
                    .map(roi -> function.apply(roi, null))
                    .collect(Collectors.toList());
        } else {
            // Two channels detected, represent using cell objects
            // We assume that the labels are matched - and we can't have a nucleus without a cell
            Map<Number, ROI> childROIs = roiMaps.get(0);
            Map<Number, ROI> parentROIs = roiMaps.get(1);
            List<PathObject> cells = new ArrayList<>();
            for (var entry : parentROIs.entrySet()) {
                var parent = entry.getValue();
                var child = childROIs.getOrDefault(entry.getKey(), null);
                var outputObject = function.apply(parent, child);
                cells.add(outputObject);
            }
            return cells;
        }
    }

    private static BiFunction<ROI, ROI, PathObject> createCellFun(Random rng) {
        return (parent, child) -> {
            var cell = PathObjects.createCellObject(parent, child);
            var color = ColorTools.packRGB(rng.nextInt(255), rng.nextInt(255), rng.nextInt(255));
            cell.setColor(color);
            return cell;
        };
    }

    private static BiFunction<ROI, ROI, PathObject> createObjectsFun(Function<ROI, PathObject> createParentFun, Function<ROI, PathObject> createChildFun, Random rng) {
        return (parent, child) -> {
            var parentObj = createParentFun.apply(parent);
            var color = ColorTools.packRGB(rng.nextInt(255), rng.nextInt(255), rng.nextInt(255));
            parentObj.setColor(color);
            if (child != null) {
                var childObj = createChildFun.apply(child);
                childObj.setColor(color);
                parentObj.addChildObject(childObj);
            }
            return parentObj;
        };
    }

}
