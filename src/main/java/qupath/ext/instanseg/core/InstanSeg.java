package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.training.util.ProgressBar;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.utils.ObjectMerger;
import qupath.lib.objects.utils.ObjectProcessor;
import qupath.lib.objects.utils.OverlapFixer;
import qupath.lib.objects.utils.Tiler;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.TaskRunnerUtils;
import qupath.lib.scripting.QP;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.IntStream;

public class InstanSeg {

    private static final Logger logger = LoggerFactory.getLogger(InstanSeg.class);

    private final int tileDims;
    private final double downsample;
    private final int padding;
    private final int boundary;
    private final int numOutputChannels;
    private final boolean randomColors;
    private final ImageData<BufferedImage> imageData;
    private final Collection<ColorTransforms.ColorTransform> channels;
    private final InstanSegModel model;
    private final Device device;
    private final TaskRunner taskRunner;
    private final Class<? extends PathObject> preferredOutputClass;

    private InstanSeg(int tileDims, double downsample, int padding, int boundary, int numOutputChannels, ImageData<BufferedImage> imageData,
                      Collection<ColorTransforms.ColorTransform> channels, InstanSegModel model, Device device, TaskRunner taskRunner,
                      Class<? extends PathObject> preferredOutputClass, boolean randomColors) {
        this.tileDims = tileDims;
        this.downsample = downsample; // Optional... and not advised (use the model spec instead); set <= 0 to ignore
        this.padding = padding;
        this.boundary = boundary;
        this.numOutputChannels = numOutputChannels;
        this.imageData = imageData;
        this.channels = channels;
        this.model = model;
        this.device = device;
        this.taskRunner = taskRunner;
        this.preferredOutputClass = preferredOutputClass;
        this.randomColors = randomColors;
    }

    /**
     * Run inference for the currently selected PathObjects.
     */
    public InstanSegResults detectObjects() {
        return detectObjects(imageData.getHierarchy().getSelectionModel().getSelectedObjects());
    }

    /**
     * Run inference for the currently selected PathObjects, then measure the new objects that were created.
     */
    public InstanSegResults detectObjectsAndMeasure() {
        return detectObjectsAndMeasure(imageData.getHierarchy().getSelectionModel().getSelectedObjects());
    }

    /**
     * Run inference for the specified selected PathObjects, then measure the new objects that were created.
     */
    public InstanSegResults detectObjectsAndMeasure(Collection<? extends PathObject> pathObjects) {
        var results = detectObjects(pathObjects);
        for (var pathObject: pathObjects) {
            makeMeasurements(imageData, pathObject.getChildObjects());
        }
        return results;
    }

    /**
     * Get the imageData from an InstanSeg object.
     * @return The imageData used for the model.
     */
    public ImageData<BufferedImage> getImageData() {
        return imageData;
    }

    /**
     * Run inference for a collection of PathObjects.
     */
    public InstanSegResults detectObjects(Collection<? extends PathObject> pathObjects) {
        return runInstanSeg(pathObjects);
    }

    /**
     * Create a builder object for InstanSeg.
     * @return A builder, which may not be valid.
     */
    public static Builder builder() {
        return new Builder();
    }


    /**
     * Utility function to make measurements for the objects created by InstanSeg.
     * @param imageData The ImageData for making measurements.
     * @param detections The objects to measure.
     */
    public void makeMeasurements(ImageData<BufferedImage> imageData, Collection<? extends PathObject> detections) {
        double downsample = model.getPreferredDownsample(imageData.getServer().getPixelCalibration());
        DetectionMeasurer.builder()
                .pixelSize(downsample)
                .build()
                .makeMeasurements(imageData, detections);
    }

    private InstanSegResults runInstanSeg(Collection<? extends PathObject> pathObjects) {

        long startTime = System.currentTimeMillis();

        Path modelPath;
        modelPath = model.getPath().resolve("instanseg.pt");
        int nPredictors = 1; // todo: change me?

        // Optionally pad images so that every tile has the required size.
        // This is useful if the model requires a specific input size - but InstanSeg should be able to handle this
        // and inference can be much faster if we permit tiles to be cropped.
        boolean padToInputSize = false;
        String layout = "CHW";

        // TODO: Remove C if not needed (added for instanseg_v0_2_0.pt) - still relevant?
        String layoutOutput = "CHW";

        // Get the downsample - this may be specified by the user, or determined from the model spec
        double downsample;
        if (this.downsample > 0) {
            downsample = this.downsample;
            logger.debug("Calling InstanSeg with user-specified downsample {}", downsample);
        } else {
            downsample = this.model.getPreferredDownsample(imageData.getServer().getPixelCalibration());
            logger.debug("Calling InstanSeg with calculated downsample {}", downsample);
        }

        try (var model = Criteria.builder()
                .setTypes(Mat.class, Mat.class)
                .optModelUrls(String.valueOf(modelPath.toUri()))
                .optProgress(new ProgressBar())
                .optDevice(device) // Remove this line if devices are problematic!
                .optTranslator(new MatTranslator(layout, layoutOutput, numOutputChannels))
                .build()
                .loadModel()) {

            BaseNDManager baseManager = (BaseNDManager)model.getNDManager();
            printResourceCount("Resource count before prediction",
                    (BaseNDManager)baseManager.getParentManager());
            baseManager.debugDump(2);
            BlockingQueue<Predictor<Mat, Mat>> predictors = new ArrayBlockingQueue<>(nPredictors);

            try {
                for (int i = 0; i < nPredictors; i++) {
                    predictors.put(model.newPredictor());
                }

                printResourceCount("Resource count after creating predictors",
                        (BaseNDManager)baseManager.getParentManager());

                int sizeWithoutPadding = (int) Math.ceil(downsample * (tileDims - (double) padding*2));
                var predictionProcessor = new TilePredictionProcessor(predictors, channels, tileDims, tileDims, padToInputSize);
                var processor = OpenCVProcessor.builder(predictionProcessor)
                        .imageSupplier((parameters) -> ImageOps.buildImageDataOp(channels)
                                .apply(parameters.getImageData(), parameters.getRegionRequest()))
                        .tiler(Tiler.builder(sizeWithoutPadding)
                                .alignCenter()
                                .cropTiles(false)
                                .build()
                        )
                        .outputHandler(
                                new PruneObjectOutputHandler<>(
                                        new InstanSegOutputToObjectConverter(preferredOutputClass, randomColors), boundary))
                        .padding(padding)
                        .postProcess(createPostProcessor())
                        .downsample(downsample)
                        .build();
                processor.processObjects(taskRunner, imageData, pathObjects);
                int nObjects = pathObjects.stream().mapToInt(PathObject::nChildObjects).sum();
                return new InstanSegResults(
                        predictionProcessor.getPixelsProcessedCount(),
                        predictionProcessor.getTilesProcessedCount(),
                        predictionProcessor.getTilesFailedCount(),
                        nObjects,
                        System.currentTimeMillis() - startTime
                );
            } finally {
                for (var predictor: predictors) {
                    predictor.close();
                }
                printResourceCount("Resource count after prediction", (BaseNDManager)baseManager.getParentManager());
            }
        } catch (Exception e) {
            logger.error("Error running InstanSeg", e);
            return new InstanSegResults(0, 0, 0, 0,
                    System.currentTimeMillis() - startTime);
        }
    }

    private static ObjectProcessor createPostProcessor() {
        var merger = ObjectMerger.createIoMinMerger(0.5);
        var fixer = OverlapFixer.builder()
                .clipOverlaps()
                .keepFragments(false)
                .sortBySolidity()
                .build();
        return merger.andThen(fixer);
    }

    /**
     * Print resource count for debugging purposes.
     * If we are not logging at debug level, do nothing.
     * @param title
     * @param manager
     */
    private static void printResourceCount(String title, BaseNDManager manager) {
        if (logger.isDebugEnabled()) {
            logger.debug(title);
            manager.debugDump(2);
        }
    }


    /**
     * A builder class for InstanSeg.
     */
    public static final class Builder {

        private static final Logger logger = LoggerFactory.getLogger(Builder.class);

        private static final int MIN_TILE_DIMS = 256;
        private static final int MAX_TILE_DIMS = 2048;

        private int tileDims = 512;
        private double downsample = -1; // Optional - we usually get this from the model
        private int padding = 80; // Previous default of 40 could miss large objects
        private int boundary = 20; // TODO: Check relationship between padding & boundary
        private int numOutputChannels = 2;
        private boolean randomColors = true;
        private Device device = Device.fromName("cpu");
        private TaskRunner taskRunner = TaskRunnerUtils.getDefaultInstance().createTaskRunner();
        private ImageData<BufferedImage> imageData;
        private Collection<ColorTransforms.ColorTransform> channels;
        private InstanSegModel model;
        private Class<? extends PathObject> preferredOutputClass;

        Builder() {}

        /**
         * Set the width and height of tiles
         * @param tileDims The tile width and height
         * @return A modified builder
         */
        public Builder tileDims(int tileDims) {
            if (tileDims < MIN_TILE_DIMS) {
                logger.warn("Tile dimensions too small, setting to minimum value of {}", MIN_TILE_DIMS);
                this.tileDims = MIN_TILE_DIMS;
            } else if (tileDims > MAX_TILE_DIMS) {
                logger.warn("Tile dimensions too large, setting to maximum value of {}", MAX_TILE_DIMS);
                this.tileDims = MAX_TILE_DIMS;
            } else {
                this.tileDims = tileDims;
            }
            return this;
        }

        /**
         * Set the downsample to be used in region requests
         * @param downsample The downsample to be used
         * @return A modified builder
         */
        public Builder downsample(double downsample) {
            this.downsample = downsample;
            return this;
        }

        /**
         * Set the padding (overlap) between tiles
         * @param padding The extra size added to tiles to allow overlap
         * @return A modified builder
         */
        public Builder interTilePadding(int padding) {
            if (padding < 0) {
                logger.warn("Padding cannot be negative, setting to 0");
                this.padding = 0;
            } else {
                this.padding = padding;
            }
            return this;
        }

        /**
         * Set the size of the overlap region between tiles
         * @param boundary The width in pixels that overlaps between tiles
         * @return A modified builder
         */
        public Builder tileBoundary(int boundary) {
            this.boundary = boundary;
            return this;
        }

        /**
         * Set the number of output channels
         * @param numOutputChannels The number of output channels (1 or 2 currently)
         * @return A modified builder
         */
        public Builder numOutputChannels(int numOutputChannels) {
            this.numOutputChannels = numOutputChannels;
            return this;
        }

        /**
         * Set the imageData to be used
         * @param imageData An imageData instance
         * @return A modified builder
         */
        public Builder imageData(ImageData<BufferedImage> imageData) {
            this.imageData = imageData;
            return this;
        }

        /**
         * Set the imageData to be used as the current image data.
         * @return A modified builder
         */
        public Builder currentImageData() {
            this.imageData = QP.getCurrentImageData();
            return this;
        }

        /**
         * Set the channels to be used in inference
         * @param channels A collection of channels to be used in inference
         * @return A modified builder
         */
        public Builder channels(Collection<ColorTransforms.ColorTransform> channels) {
            this.channels = channels;
            return this;
        }

        /**
         * Set the channels to be used in inference
         * @param channels Channels to be used in inference
         * @return A modified builder
         */
        public Builder channels(ColorTransforms.ColorTransform channel, ColorTransforms.ColorTransform... channels) {
            var l = Arrays.asList(channels);
            l.add(channel);
            this.channels = l;
            return this;
        }

        /**
         * Set the model to use all channels for inference
         * @return A modified builder
         */
        public Builder allChannels() {
            // assignment is just to suppress IDE suggestion for void return val
            var tmp = channelIndices(
                    IntStream.range(0, imageData.getServer().nChannels())
                            .boxed()
                            .toList());
            return this;
        }

        /**
         * Set the channels using indices
         * @param channels Integers used to specify the channels used
         * @return A modified builder
         */
        public Builder channelIndices(Collection<Integer> channels) {
            this.channels = channels.stream()
                    .map(ColorTransforms::createChannelExtractor)
                    .toList();
            return this;
        }

        /**
         * Set the channels using indices
         * @param channels Integers used to specify the channels used
         * @return A modified builder
         */
        public Builder channelIndices(int channel, int... channels) {
            List<ColorTransforms.ColorTransform> l = new ArrayList<>();
            l.add(ColorTransforms.createChannelExtractor(channel));
            for (int i: channels) {
                l.add(ColorTransforms.createChannelExtractor(i));
            }
            this.channels = l;
            return this;
        }

        /**
         * Set the channel names to be used
         * @param channels A set of channel names
         * @return A modified builder
         */
        public Builder channelNames(Collection<String> channels) {
            this.channels = channels.stream()
                    .map(ColorTransforms::createChannelExtractor)
                    .toList();
            return this;
        }

        /**
         * Set the channel names to be used
         * @param channels A set of channel names
         * @return A modified builder
         */
        public Builder channelNames(String channel, String... channels) {
            List<ColorTransforms.ColorTransform> l = new ArrayList<>();
            l.add(ColorTransforms.createChannelExtractor(channel));
            for (String s: channels) {
                l.add(ColorTransforms.createChannelExtractor(s));
            }
            this.channels = l;
            return this;
        }

        /**
         * Request that random colors be used for the output objects.
         * @return
         */
        public Builder randomColors() {
            return randomColors(true);
        }

        /**
         * Optionally request that random colors be used for the output objects.
         * @param doRandomColors
         * @return
         */
        public Builder randomColors(boolean doRandomColors) {
            this.randomColors = doRandomColors;
            return this;
        }

        /**
         * Set the number of threads used
         * @param nThreads The number of threads to be used
         * @return A modified builder
         */
        public Builder nThreads(int nThreads) {
            this.taskRunner = TaskRunnerUtils.getDefaultInstance().createTaskRunner(nThreads);
            return this;
        }

        /**
         * Set the TaskRunner
         * @param taskRunner An object that will run tasks and show progress
         * @return A modified builder
         */
        public Builder taskRunner(TaskRunner taskRunner) {
            this.taskRunner = taskRunner;
            return this;
        }

        /**
         * Set the specific model to be used
         * @param model An already instantiated InstanSeg model.
         * @return A modified builder
         */
        public Builder model(InstanSegModel model) {
            this.model = model;
            return this;
        }

        /**
         * Set the specific model by path
         * @param path A path on disk to create an InstanSeg model from.
         * @return A modified builder
         */
        public Builder modelPath(Path path) throws IOException {
            return model(InstanSegModel.fromPath(path));
        }

        /**
         * Set the specific model by path
         * @param path A path on disk to create an InstanSeg model from.
         * @return A modified builder
         */
        public Builder modelPath(String path) throws IOException {
            return modelPath(Path.of(path));
        }

        /**
         * Set the specific model to be used
         * @param name The name of a built-in model
         * @return A modified builder
         */
        public Builder modelName(String name) {
            return model(InstanSegModel.fromName(name));
        }

        /**
         * Set the device to be used
         * @param deviceName The name of the device to be used (eg, "gpu", "mps").
         * @return A modified builder
         */
        public Builder device(String deviceName) {
            this.device = Device.fromName(deviceName);
            return this;
        }

        /**
         * Set the device to be used
         * @param device The {@link Device} to be used
         * @return A modified builder
         */
        public Builder device(Device device) {
            this.device = device;
            return this;
        }

        /**
         * Specify cells as the output class, possibly without nuclei
         * @return A modified builder
         */
        public Builder outputCells() {
            this.preferredOutputClass = PathCellObject.class;
            return this;
        }

        /**
         * Specify (possibly nested) detections as the output class
         * @return A modified builder
         */
        public Builder outputDetections() {
            this.preferredOutputClass = PathDetectionObject.class;
            return this;
        }

        /**
         * Specify (possibly nested) annotations as the output class
         * @return A modified builder
         */
        public Builder outputAnnotations() {
            this.preferredOutputClass = PathAnnotationObject.class;
            return this;
        }

        /**
         * Build the InstanSeg instance.
         * @return An InstanSeg instance ready for object detection.
         */
        public InstanSeg build() {
            if (imageData == null) {
                // assignment is just to suppress IDE suggestion for void return
                var tmp = currentImageData();
            }
            if (channels == null) {
                var tmp = allChannels();
            }
            return new InstanSeg(
                    this.tileDims,
                    this.downsample,
                    this.padding,
                    this.boundary,
                    this.numOutputChannels,
                    this.imageData,
                    this.channels,
                    this.model,
                    this.device,
                    this.taskRunner,
                    this.preferredOutputClass,
                    this.randomColors);
        }

    }

}
