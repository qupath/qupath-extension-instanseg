package qupath.ext.instanseg.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.objects.PathObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class DetectionMeasurer {
    private static final Logger logger = LoggerFactory.getLogger(DetectionMeasurer.class);

    private final double pixelSize;
    private final ImageData<BufferedImage> imageData;
    private final Collection<ObjectMeasurements.Compartments> compartments;
    private final Collection<ObjectMeasurements.Measurements> measurements;
    private final Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures;

    private DetectionMeasurer(ImageData<BufferedImage> imageData,
                              Collection<ObjectMeasurements.Compartments> compartments,
                              Collection<ObjectMeasurements.Measurements> measurements,
                              Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures,
                              double pixelSize) {
        this.shapeFeatures = shapeFeatures;
        this.pixelSize = pixelSize;
        this.imageData = imageData;
        this.compartments = compartments;
        this.measurements = measurements;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void makeMeasurements(Collection<PathObject> detections) {
        var server = imageData.getServer();
        var resolution = server.getPixelCalibration();
        var pixelCal = server.getPixelCalibration();

        if (Double.isFinite(pixelSize) && pixelSize > 0) {
            double downsample = pixelSize / resolution.getAveragedPixelSize().doubleValue();
            resolution = resolution.createScaledInstance(downsample, downsample);
        }
        ObjectMeasurements.ShapeFeatures[] featuresArray = shapeFeatures.toArray(new ObjectMeasurements.ShapeFeatures[0]);
        detections.parallelStream().forEach(c -> ObjectMeasurements.addShapeMeasurements(c, pixelCal, featuresArray));

        if (!detections.isEmpty()) {
            logger.info("Making measurements for {} objects", detections.size());
            var stains = imageData.getColorDeconvolutionStains();
            var builder = new TransformedServerBuilder(server);
            if (stains != null) {
                List<Integer> stainNumbers = new ArrayList<>();
                for (int s = 1; s <= 3; s++) {
                    if (!stains.getStain(s).isResidual())
                        stainNumbers.add(s);
                }
                builder.deconvolveStains(stains, stainNumbers.stream().mapToInt(i -> i).toArray());
            }

            try (var server2 = builder.build()) {

                double downsample = resolution.getAveragedPixelSize().doubleValue() / pixelCal.getAveragedPixelSize().doubleValue();

                detections.parallelStream().forEach(cell -> {
                    try {
                        ObjectMeasurements.addIntensityMeasurements(server2, cell, downsample, measurements, compartments);
                    } catch (IOException e) {
                        logger.info(e.getLocalizedMessage(), e);
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }
    }


    public static class Builder {
        private ImageData<BufferedImage> imageData;
        private Collection<ObjectMeasurements.Compartments> compartments = Arrays.asList(ObjectMeasurements.Compartments.values());
        private Collection<ObjectMeasurements.Measurements> measurements = Arrays.asList(ObjectMeasurements.Measurements.values());
        private Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures = Arrays.asList(ObjectMeasurements.ShapeFeatures.values());
        private double pixelSize;

        public Builder imageData(ImageData<BufferedImage> imageData) {
            this.imageData = imageData;
            return this;
        }

        public Builder pixelSize(double pixelSize) {
            this.pixelSize = pixelSize;
            return this;
        }

        public Builder compartments(Collection<ObjectMeasurements.Compartments> compartments) {
            this.compartments = compartments;
            return this;
        }

        public Builder measurements(Collection<ObjectMeasurements.Measurements> measurements) {
            this.measurements = measurements;
            return this;
        }

        public Builder shapeFeatures(Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures) {
            this.shapeFeatures = shapeFeatures;
            return this;
        }

        public DetectionMeasurer build() {
            return new DetectionMeasurer(imageData, compartments, measurements, shapeFeatures, pixelSize);
        }
    }
}
