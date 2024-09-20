package qupath.ext.instanseg.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.PathTask;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.TaskRunnerUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Helper class for adding measurements to InstanSeg detections.
 * <p>
 * Note that this is inherently limited to 'small' detections, where the entire ROI can be loaded into memory.
 * It does not support measuring arbitrarily large regions at a high resolution.
 */
class DetectionMeasurer {

    private static final Logger logger = LoggerFactory.getLogger(DetectionMeasurer.class);

    private final TaskRunner taskRunner;

    private final Collection<ObjectMeasurements.Compartments> compartments;
    private final Collection<ObjectMeasurements.Measurements> measurements;
    private final Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures;
    private final double downsample;

    private DetectionMeasurer(TaskRunner taskRunner,
                              Collection<ObjectMeasurements.Compartments> compartments,
                              Collection<ObjectMeasurements.Measurements> measurements,
                              Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures,
                              double downsample) {
        this.taskRunner = taskRunner;
        this.shapeFeatures = shapeFeatures;
        this.compartments = compartments;
        this.measurements = measurements;
        this.downsample = downsample;
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
    public void makeMeasurements(ImageData<BufferedImage> imageData, Collection<? extends PathObject> objects) {
        if (objects.isEmpty()) {
            return;
        }

        var server = imageData.getServer();
        var pixelCal = server.getPixelCalibration();

        ObjectMeasurements.ShapeFeatures[] featuresArray = shapeFeatures.toArray(new ObjectMeasurements.ShapeFeatures[0]);

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

        try {
            // Submit all the measurement tasks
            var server2 = builder.build();
            List<PathTask> tasks = new ArrayList<>();
            for (var cell : objects) {
                tasks.add(new MeasurementTask(server2, cell, downsample, featuresArray, compartments, measurements));
            }
            String message = objects.size() == 1 ? "Measuring 1 object" : "Measuring " + objects.size() + " objects";
            taskRunner.runTasks(message, tasks);
            // It's possible we have the same server - in which case we don't want to close it twice
            if (!Objects.equals(server, server2)) {
                server2.close();
            }
        } catch (Exception e) {
            logger.error("Exception when creating intensity measurements", e);
        }

    }

    private static class MeasurementTask implements PathTask {

        private final ImageServer<BufferedImage> server;
        private final PathObject detection;
        private final double downsample;
        private final ObjectMeasurements.ShapeFeatures[] featuresArray;
        private final Collection<ObjectMeasurements.Compartments> compartments;
        private final Collection<ObjectMeasurements.Measurements> measurements;

        private String lastError;

        private MeasurementTask(ImageServer<BufferedImage> server, PathObject detection, double downsample,
                                ObjectMeasurements.ShapeFeatures[] featuresArray, Collection<ObjectMeasurements.Compartments> compartments,
                                Collection<ObjectMeasurements.Measurements> measurements) {
            this.server = server;
            this.detection = detection;
            this.downsample = downsample;
            // No defensive copy needed only because this is private
            this.featuresArray = featuresArray;
            this.compartments = compartments;
            this.measurements = measurements;
        }

        @Override
        public String getLastResultsDescription() {
            return lastError == null ? "Measured " + detection : "Completed with error: " + lastError;
        }

        @Override
        public void run() {
            try {
                if (featuresArray.length > 0) {
                    ObjectMeasurements.addShapeMeasurements(detection, server.getPixelCalibration(), featuresArray);
                }
                ObjectMeasurements.addIntensityMeasurements(server, detection, downsample, measurements, compartments);
            } catch (IOException e) {
                lastError = e.getLocalizedMessage();
                logger.error("Exception adding measurements: {}", e.getMessage(), e);
            }
        }
    }


    /**
     * A builder class for DetectionMeasurer.
     */
    static class Builder {

        private TaskRunner taskRunner;
        private Collection<ObjectMeasurements.Compartments> compartments = Arrays.asList(ObjectMeasurements.Compartments.values());
        private Collection<ObjectMeasurements.Measurements> measurements = Arrays.stream(ObjectMeasurements.Measurements.values())
                .filter(m -> m != ObjectMeasurements.Measurements.VARIANCE) // Skip variance - we have standard deviation
                .toList();
        private Collection<ObjectMeasurements.ShapeFeatures> shapeFeatures = Arrays.asList(ObjectMeasurements.ShapeFeatures.values());
        private double downsample;

        /**
         * Specify the task runner used to run parallel tasks.
         * @param taskRunner
         * @return
         */
        public Builder taskRunner(TaskRunner taskRunner) {
            this.taskRunner = taskRunner;
            return this;
        }

        /**
         * Set the downsample that the measurer should use.
         * The measurer is assumed to be short-lived and used for a single image, and so doesn't need to maintain a
         * value related to pixel size. Therefore, it takes an explicit downsample to avoid any risk that the
         * pixel-size-to-downsample calculation here wouldn't match that done during detection.
         *
         * @param downsample The downsample that detections/annotations/etc should be made at.
         * @return A modified builder.
         */
        public Builder downsample(double downsample) {
            this.downsample = downsample;
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
            var runner = taskRunner == null ? TaskRunnerUtils.getDefaultInstance().createTaskRunner() : taskRunner;
            return new DetectionMeasurer(runner, compartments, measurements, shapeFeatures, downsample);
        }
    }
}
