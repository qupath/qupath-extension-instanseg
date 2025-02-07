package qupath.ext.instanseg.core;

import org.bytedeco.opencv.opencv_core.Mat;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.tensor.OutputTensor;
import qupath.bioimageio.spec.tensor.Tensors;
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
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.tools.OpenCVTools;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

class InstanSegOutputToObjectConverter implements OutputHandler.OutputToObjectConverter<Mat, Mat, Mat[]> {

    private static final Logger logger = LoggerFactory.getLogger(InstanSegOutputToObjectConverter.class);

    private final Class<? extends PathObject> preferredObjectClass;

    /**
     * Assign random colors to the objects.
     * This may be turned off or made optional in the future.
     */
    private final boolean randomColors;
    private final List<OutputTensor> outputTensors;

    InstanSegOutputToObjectConverter(List<OutputTensor> outputTensors,
                                     Class<? extends PathObject> preferredOutputType,
                                     boolean randomColors) {
        this.outputTensors = outputTensors;
        this.preferredObjectClass = preferredOutputType;
        this.randomColors = randomColors;
    }

    @Override
    public List<PathObject> convertToObjects(Parameters<Mat, Mat> params, Mat[] output) {
        if (output == null) {
            return List.of();
        }
        var matLabels = output[0];
        int nChannels = matLabels.channels();
        if (nChannels < 1 || nChannels > 2)
            throw new IllegalArgumentException("Expected 1 or 2 channels, but found " + nChannels);


        List<Map<Number, ROI>> roiMaps = new ArrayList<>();
        ImagePlane plane = params.getRegionRequest().getImagePlane();
        for (var mat : OpenCVTools.splitChannels(matLabels)) {
            var image = OpenCVTools.matToSimpleImage(mat, 0);
            var geoms = ContourTracing.createGeometries(image, params.getRegionRequest(), 1, -1);
            roiMaps.add(geoms.entrySet().stream()
                    .collect(
                            Collectors.toMap(
                                    Map.Entry::getKey,
                                    entry -> geometryToFilledROI(entry.getValue(), plane)))
            );
        }

        // "instance segmentation" "cell embeddings" "cell classes" "cell probabilities" "semantic segmentation"
        // If we have two outputs, the second may give classifications - arrange by row
        List<Map<Number, double[]>> auxiliaryValues = new ArrayList<>();
        auxiliaryValues.add(new HashMap<>());
        if (output.length > 1) {
            Map<Number, double[]> auxVals = new HashMap<>();
            for (int i = 1; i < output.length; i++) {
                var matClass = output[i];
                int nRows = matClass.rows();
                for (int r = 0; r < nRows; r++) {
                    double[] doubles = OpenCVTools.extractDoubles(matClass.row(r));
                    auxVals.put(r+1, doubles);
                }
                auxiliaryValues.add(auxVals);
            }
        }

        // We reverse the order because the smaller output (e.g. nucleus) comes before the larger out (e.g. cell)
        // and we want to iterate in the opposite order. If this changes (or becomes inconsistent) we may need to
        // sum pixels or areas.
        if (roiMaps.size() > 1)
            roiMaps = roiMaps.reversed();

        boolean createCells = Objects.equals(PathCellObject.class, preferredObjectClass) ||
                (roiMaps.size() == 2 && preferredObjectClass == null);

        List<PathObject> pathObjects;
        Map<String, List<String>> outputNameToClasses = fetchOutputClasses(outputTensors);
        if (createCells) {
            Map<Number, ROI> parentROIs = roiMaps.get(0);
            Map<Number, ROI> childROIs = roiMaps.size() >= 2 ? roiMaps.get(1) : Collections.emptyMap();
            pathObjects = parentROIs.entrySet().stream().map(entry -> {
                var parent = entry.getValue();
                var label = entry.getKey();
                var child = childROIs.getOrDefault(label, null);
                var cell = PathObjects.createCellObject(parent, child);
                for (int i = 1; i < output.length; i++) {
                    // todo: handle paired logits and class labels
                    handleAuxOutput(
                            cell,
                            auxiliaryValues.get(i).getOrDefault(label, null),
                            outputTensors.get(i),
                            outputNameToClasses.get(outputTensors.get(i).getName())
                    );
                }
                return cell;
            }).toList();
        } else {
            Function<ROI, PathObject> createObjectFun = createObjectFun(preferredObjectClass);
            pathObjects = new ArrayList<>();
            Map<Number, ROI> parentMap = roiMaps.getFirst();
            List<Map<Number, ROI>> childMaps = roiMaps.size() == 1 ? Collections.emptyList() : roiMaps.subList(1, roiMaps.size());
            for (var entry : parentMap.entrySet()) {
                var label = entry.getKey();
                var roi = entry.getValue();
                var pathObject = createObjectFun.apply(roi);
                if (roiMaps.size() > 1) {
                    for (var subMap : childMaps) {
                        var childROI = subMap.get(label);
                        if (childROI != null) {
                            var childObject = createObjectFun.apply(childROI);
                            pathObject.addChildObject(childObject);
                        }
                    }
                }
                for (int i = 1; i < output.length; i++) {
                    // todo: handle paired logits and class labels
                    handleAuxOutput(
                            pathObject,
                            auxiliaryValues.get(i).getOrDefault(label, null),
                            outputTensors.get(i),
                            outputNameToClasses.get(outputTensors.get(i).getName())
                    );
                }
                pathObjects.add(pathObject);
            }
        }

        if (randomColors) {
            var rng = new Random(params.getRegionRequest().hashCode());
            pathObjects.forEach(p -> assignRandomColor(p, rng));
        }

        return pathObjects;
    }

    private Map<String, List<String>> fetchOutputClasses(List<OutputTensor> outputTensors) {
        Map<String, List<String>> out = new HashMap<>();
        // todo: loop through and check type
        // if there's only one output, or if there's no pairing, then return nothing
        if (outputTensors.size() == 1) {
            return out;
        }

        var classOutputs = outputTensors.stream().filter(ot -> ot.getName().startsWith("detection_classes")).toList();
        var logitOutputs = outputTensors.stream().filter(ot -> ot.getName().startsWith("detection_logits")).toList();

        var classTypeToClassNames = classOutputs.stream()
                .collect(
                        Collectors.toMap(
                                co -> co.getName(),
                                co -> getClassNames(co)
                        )
                );
        out.putAll(classTypeToClassNames);
        // try to find logits that correspond to classes (eg, detection_classes_foo and detection_logits_foo)
        var logitNameToClassName = logitOutputs.stream().collect(Collectors.toMap(
                lo -> lo.getName(),
                lo -> {
                    var matchingClassNames = classTypeToClassNames.entrySet().stream()
                        .filter(es -> {
                            return es.getKey().replace("detection_classes_", "").equals(lo.getName().replace("detection_logits_", ""));
                        }).toList();
                    if (matchingClassNames.size() > 1) {
                        logger.warn("More than one matching class name for logits {}, choosing the first", lo.getName());
                    } else if (matchingClassNames.size() == 0) {
                        // try to get default class names anyway...
                        return getClassNames(lo);
                    }
                    return matchingClassNames.getFirst().getValue();
            }));
        out.putAll(logitNameToClassName);
        return out;
    }

    private List<String> getClassNames(OutputTensor outputTensor) {
        var description = outputTensor.getDataDescription();
        List<String> outputClasses;
        if (description != null && description instanceof Tensors.NominalOrOrdinalDataDescription dataDescription) {
            outputClasses = dataDescription.getValues().stream().map(Object::toString).toList();
        } else {
            outputClasses = new ArrayList<>();
            int nClasses = outputTensor.getShape().getShape()[2]; // output axes are batch, index, class
            for (int i = 0; i < nClasses; i++) {
                outputClasses.add("Class" + i);
            }
        }
        return outputClasses;
    }

    private static void handleAuxOutput(PathObject pathObject, double[] values, OutputTensor outputTensor, List<String> outputClasses) {
        if (values == null)
            return;
        var outputType = InstanSegModel.OutputTensorType.fromString(outputTensor.getName());
        if (outputType.isEmpty()) {
            return;
        }
        switch(outputType.get()) {
            case DETECTION_LOGITS -> {
                // we could also assign classes here, but assume for now this is handled internally and supplied as binary output
                try (var ml = pathObject.getMeasurementList()) {
                    for (int i = 0; i < values.length; i++) {
                        double val = values[i];
                        ml.put("Logit " + outputClasses.get(i), val);
                    }
                }
            }
            case DETECTION_CLASSES -> {
                for (double val : values) {
                    pathObject.setPathClass(PathClass.fromString(outputClasses.get((int) val)));
                }
            }
            case DETECTION_EMBEDDINGS -> {
                try (var ml = pathObject.getMeasurementList()) {
                    for (int i = 0; i < values.length; i++) {
                        double val = values[i];
                        ml.put("Embedding " + i, val);
                    }
                }
            }
            case SEMANTIC_SEGMENTATION -> {
                throw new UnsupportedOperationException("No idea what to do here!");
            }
        }
    }


    /**
     * Assign a random color to a PathObject and all descendants, returning the object.
     *
     * @param pathObject The PathObject
     * @param rng A random number generator.
     */
    private static void assignRandomColor(PathObject pathObject, Random rng) {
        pathObject.setColor(randomRGB(rng));
        for (var child : pathObject.getChildObjects()) {
            assignRandomColor(child, rng);
        }
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
            return InstanSegOutputToObjectConverter::createLockedAnnotation;
        else if (Objects.equals(PathTileObject.class, preferredObjectClass))
            return PathObjects::createTileObject;
        else
            throw new UnsupportedOperationException("Unsupported object class: " + preferredObjectClass);
    }

    /**
     * Create annotations that are locked by default, to reduce the risk of editing them accidentally.
     * @param roi The region of interest
     * @return A locked annotation object.
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
