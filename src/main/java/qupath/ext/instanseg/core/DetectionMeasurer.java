package qupath.ext.instanseg.core;

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

    private final Collection<ObjectMeasurements.Compartments> compartments;
    private final Collection<ObjectMeasurements.Measurements> measurements;
    private final Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures;
    private final double pixelSize;

    private DetectionMeasurer(Collection<ObjectMeasurements.Compartments> compartments,
                              Collection<ObjectMeasurements.Measurements> measurements,
                              Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures,
                              double pixelSize) {
        this.shapeFeatures = shapeFeatures;
        this.pixelSize = pixelSize;
        this.compartments = compartments;
        this.measurements = measurements;
    }

    /**
     * Create a builder object for a DetectionMeasurer.
     * @return A builder that may or may not be valid.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Make and add measurements for the specified objects using the builder's
     * existing (immutable?) configuration.
     * @param imageData The imageData to be used for measuring the pathobject with.
     * @param objects The objects to be measured.
     */
    public void makeMeasurements(ImageData<BufferedImage> imageData, Collection<PathObject> objects) {
        var server = imageData.getServer();
        var resolution = server.getPixelCalibration();
        var pixelCal = server.getPixelCalibration();

        if (Double.isFinite(pixelSize) && pixelSize > 0) {
            double downsample = pixelSize / resolution.getAveragedPixelSize().doubleValue();
            resolution = resolution.createScaledInstance(downsample, downsample);
        }
        ObjectMeasurements.ShapeFeatures[] featuresArray = shapeFeatures.toArray(new ObjectMeasurements.ShapeFeatures[0]);
        objects.parallelStream().forEach(c -> ObjectMeasurements.addShapeMeasurements(c, pixelCal, featuresArray));

        if (!objects.isEmpty()) {
            logger.info("Making measurements for {} objects", objects.size());
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

                objects.parallelStream().forEach(cell -> {
                    try {
                        ObjectMeasurements.addIntensityMeasurements(server2, cell, downsample, measurements, compartments);
                    } catch (IOException e) {
                        logger.info(e.getLocalizedMessage(), e);
                    }
                });
            } catch (Exception e) {
                logger.error("Unable to create transformed server, can't make intensity measurements", e);
            }

        }
    }


    /**
     * A builder class for DetectionMeasurer.
     */
    public static class Builder {
        private Collection<ObjectMeasurements.Compartments> compartments = Arrays.asList(ObjectMeasurements.Compartments.values());
        private Collection<ObjectMeasurements.Measurements> measurements = Arrays.asList(ObjectMeasurements.Measurements.values());
        private Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures = Arrays.asList(ObjectMeasurements.ShapeFeatures.values());
        private double pixelSize;

        /**
         * Set the pixel size that measurements should be made at.
         * @param pixelSize The pixel size that detections/annotations/etc were made at.
         * @return A modified builder.
         */
        public Builder pixelSize(double pixelSize) {
            this.pixelSize = pixelSize;
            return this;
        }

        /**
         * Change the compartments used in measuring.
         * @param compartments The compartments that should be used for measuring.
         * @return A modified builder.
         */
        public Builder compartments(Collection<ObjectMeasurements.Compartments> compartments) {
            this.compartments = compartments;
            return this;
        }

        /**
         * Change the intensity measurements to be made.
         * @param measurements The intensity measurements to be made.
         * @return A modified builder.
         */
        public Builder measurements(Collection<ObjectMeasurements.Measurements> measurements) {
            this.measurements = measurements;
            return this;
        }

        /**
         * Change the shape measurements to be made.
         * @param shapeFeatures The shape measurements to be made.
         * @return A modified builder.
         */
        public Builder shapeFeatures(Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures) {
            this.shapeFeatures = shapeFeatures;
            return this;
        }

        /**
         * Build the measurer.
         * @return An immutable detection measurer.
         */
        public DetectionMeasurer build() {
            return new DetectionMeasurer(compartments, measurements, shapeFeatures, pixelSize);
        }
    }
}
