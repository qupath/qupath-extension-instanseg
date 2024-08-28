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
import qupath.lib.objects.PathTileObject;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.tools.OpenCVTools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

class InstansegOutputToObjectConverter implements OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> {

    private static final Logger logger = LoggerFactory.getLogger(InstansegOutputToObjectConverter.class);

    private static final long seed = 1243;
    private final Class<? extends PathObject> preferredObjectClass;

    /**
     * Assign random colors to the objects.
     * This may be turned off or made optional in the future.
     */
    private boolean assignRandomColors = true;

    InstansegOutputToObjectConverter(Class<? extends PathObject> preferredObjectClass) {
        this.preferredObjectClass = preferredObjectClass;
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
        ImagePlane plane = params.getRegionRequest().getImagePlane();
        for (var mat : OpenCVTools.splitChannels(output)) {
            var image = OpenCVTools.matToSimpleImage(mat, 0);
            var geoms = ContourTracing.createGeometries(image, params.getRegionRequest(), 1, -1);
            roiMaps.add(geoms.entrySet().stream()
                    .collect(
                            Collectors.toMap(
                                    Map.Entry::getKey,
                                    entry -> geometryToFilledROI(entry.getValue(), plane)))
            );
        }

        // We reverse the order because the smaller output (e.g. nucleus) comes before the larger out (e.g. cell)
        // and we want to iterate in the opposite order. If this changes (or becomes inconsistent) we may need to
        // sum pixels or areas.
        if (roiMaps.size() > 1)
            roiMaps = roiMaps.reversed();

        boolean createCells = Objects.equals(PathCellObject.class, preferredObjectClass) ||
                (roiMaps.size() == 2 && preferredObjectClass == null);

        List<PathObject> pathObjects;
        if (createCells) {
            Map<Number, ROI> parentROIs = roiMaps.get(0);
            Map<Number, ROI> childROIs = roiMaps.size() >= 2 ? roiMaps.get(1) : Collections.emptyMap();
            pathObjects = parentROIs.entrySet().stream().map(entry -> {
                var parent = entry.getValue();
                var child = childROIs.getOrDefault(entry.getKey(), null);
                return PathObjects.createCellObject(parent, child);
            }).toList();
        } else {
            Function<ROI, PathObject> createObjectFun = createObjectFun(preferredObjectClass);
            pathObjects = new ArrayList<>();
            Map<Number, ROI> parentMap = roiMaps.getFirst();
            List<Map<Number, ROI>> childMaps = roiMaps.size() == 1 ? Collections.emptyList() : roiMaps.subList(1, roiMaps.size());
            for (var entry : parentMap.entrySet()) {
                var roi = entry.getValue();
                var pathObject = createObjectFun.apply(roi);
                if (roiMaps.size() > 1) {
                    for (var subMap : childMaps) {
                        var childROI = subMap.get(entry.getKey());
                        if (childROI != null) {
                            var childObject = createObjectFun.apply(childROI);
                            pathObject.addChildObject(childObject);
                        }
                    }
                }
                pathObjects.add(pathObject);
            }
        }

        if (assignRandomColors) {
            var rng = new Random(seed);
            pathObjects.forEach(p -> assignRandomColor(p, rng));
        }

        return pathObjects;
    }

    /**
     * Assign a random color to a PathObject and all descendants, returning the object.
     * @param pathObject
     * @param rng
     * @return
     */
    private static PathObject assignRandomColor(PathObject pathObject, Random rng) {
        pathObject.setColor(randomRGB(rng));
        for (var child : pathObject.getChildObjects()) {
            assignRandomColor(child, rng);
        }
        return pathObject;
    }

    private static ROI geometryToFilledROI(Geometry geom, ImagePlane plane) {
        if (geom == null)
            return null;
        geom = GeometryTools.fillHoles(geom);
        geom = GeometryTools.findLargestPolygon(geom);
        return GeometryTools.geometryToROI(geom, plane);
    }

    private static Function<ROI, PathObject> createObjectFun(Class<? extends PathObject> preferredObjectClass) {
        if (preferredObjectClass == null || Objects.equals(PathDetectionObject.class, preferredObjectClass))
            return PathObjects::createDetectionObject;
        else if (Objects.equals(PathAnnotationObject.class, preferredObjectClass))
            return InstansegOutputToObjectConverter::createLockedAnnotation;
        else if (Objects.equals(PathTileObject.class, preferredObjectClass))
            return PathObjects::createTileObject;
        else
            throw new UnsupportedOperationException("Unsupported object class: " + preferredObjectClass);
    }

    /**
     * Create annotations that are locked by default, to reduce the risk of editing them accidentally.
     * @param roi
     * @return
     */
    private static PathObject createLockedAnnotation(ROI roi) {
        var annotation = PathObjects.createAnnotationObject(roi);
        annotation.setLocked(true);
        return annotation;
    }

    private static int randomRGB(Random rng) {
        return ColorTools.packRGB(rng.nextInt(255), rng.nextInt(255), rng.nextInt(255));
    }

}
