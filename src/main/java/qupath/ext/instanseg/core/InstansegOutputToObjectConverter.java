package qupath.ext.instanseg.core;

import org.bytedeco.opencv.opencv_core.Mat;
import org.locationtech.jts.geom.Geometry;
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
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
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


        List<Map<Number, Geometry>> geomMaps = new ArrayList<>();
        for (var mat : OpenCVTools.splitChannels(output)) {
            var image = OpenCVTools.matToSimpleImage(mat, 0);
            geomMaps.add(
                    ContourTracing.createGeometries(image, params.getRegionRequest(), 1, -1)
            );
        }
        var rng = new Random(seed);

        BiFunction<ROI, ROI, PathObject> roisToPathObjectsFunction;
        ImagePlane plane = params.getRegionRequest().getImagePlane();
        if (classes.size() == 1) {
            roisToPathObjectsFunction = getOneClassBiFunction(classes, rng);
        } else {
            // if of length 2, then can be:
            // detection <- annotation, annotation <- annotation, detection <- detection
            assert classes.size() == 2;
            roisToPathObjectsFunction = getTwoClassBiFunction(classes, rng);
        }

        List<PathObject> cells;
        if (geomMaps.size() == 1) {
            // One-channel detected, represent using detection objects
            cells = geomMaps.get(0).values().parallelStream()
                    .map(geom -> roisToPathObjectsFunction.
                            apply(geometryToFilledROI(geom, plane), null))
                    .collect(Collectors.toList());
        } else {
            // Two channels detected, represent using cell objects
            // We assume that the labels are matched - and we can't have a nucleus without a cell
            Map<Number, Geometry> childGeoms = geomMaps.get(0);
            Map<Number, Geometry> parentGeoms = geomMaps.get(1);
            cells = parentGeoms.entrySet().parallelStream().map(entry -> {
                var parent = geometryToFilledROI(entry.getValue(), plane);
                var child = geometryToFilledROI(childGeoms.getOrDefault(entry.getKey(), null), plane);
                return roisToPathObjectsFunction.apply(parent, child);

            }).collect(Collectors.toList());
        }
        return cells;

    }

    private static ROI geometryToFilledROI(Geometry geom, ImagePlane plane) {
        if (geom == null)
            return null;
        geom = GeometryTools.fillHoles(geom);
        geom = GeometryTools.findLargestPolygon(geom);
        return GeometryTools.geometryToROI(geom, plane);
    }

    private static BiFunction<ROI, ROI, PathObject> getOneClassBiFunction(List<Class<? extends PathObject>> classes, Random rng) {
        // if of length 1, can be
        // cellObject (with or without nucleus), annotations, detections
        if (classes.get(0) == PathAnnotationObject.class) {
            return createObjectsFun(PathObjects::createAnnotationObject, PathObjects::createAnnotationObject, rng);
        } else if (classes.get(0) == PathDetectionObject.class) {
            return createObjectsFun(PathObjects::createDetectionObject, PathObjects::createDetectionObject, rng);
        } else if (classes.get(0) == PathCellObject.class) {
            return createCellFun(rng);
        } else {
            logger.warn("Unknown output {}, defaulting to cells", classes.get(0));
            return createCellFun(rng);
        }
    }

    private static BiFunction<ROI, ROI, PathObject> getTwoClassBiFunction(List<Class<? extends PathObject>> classes, Random rng) {
        Function<ROI, PathObject> fun0, fun1;
        var knownClasses = List.of(PathDetectionObject.class, PathAnnotationObject.class);
        if (!knownClasses.contains(classes.get(0)) || !knownClasses.contains(classes.get(1))) {
            logger.warn("Unknown combination of outputs {} <- {}, defaulting to cells", classes.get(0), classes.get(1));
            return createCellFun(rng);
        }
        if (classes.get(0) == PathDetectionObject.class) {
            fun0 = PathObjects::createDetectionObject;
        } else {
            fun0 = PathObjects::createAnnotationObject;
        }
        if (classes.get(1) == PathDetectionObject.class) {
            fun1 = PathObjects::createDetectionObject;
        } else {
            fun1 = PathObjects::createAnnotationObject;
        }
        return createObjectsFun(fun0, fun1, rng);
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
