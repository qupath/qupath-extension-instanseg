package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.BaseNDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.training.util.ProgressBar;
import java.util.Comparator;
import java.util.Random;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.bioimageio.spec.tensor.OutputTensor;
import qupath.lib.common.ColorTools;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.experimental.pixels.PixelProcessor;
import qupath.lib.experimental.pixels.Processor;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.utils.MeasurementStrategy;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class InstanSeg {

    private static final Logger logger = LoggerFactory.getLogger(InstanSeg.class);

    private final int tileDims;
    private final double downsample;
    private final int padding;
    private final int[] outputChannels;
    private final boolean randomColors;
    private final boolean makeMeasurements;
    private final List<ColorTransforms.ColorTransform> inputChannels;
    private final InstanSegModel model;
    private final Device device;
    private final TaskRunner taskRunner;
    private final Class<? extends PathObject> preferredOutputType;
    private final Map<String, Object> optionalArgs = new LinkedHashMap<>();

    // This was previously an adjustable parameter, but it's now fixed at 1 because we handle overlaps differently.
    // However, we might want to reinstate it, possibly as a proportion of the padding amount.
    private final int boundaryThreshold = 1;


    private InstanSeg(Builder builder) {
        this.tileDims = builder.tileDims;
        this.downsample = builder.downsample; // Optional... and not advised (use the model spec instead); set <= 0 to ignore
        this.padding = builder.padding;
        this.outputChannels = builder.outputChannels == null ? null : builder.outputChannels.clone();
        this.inputChannels = builder.channels == null ? Collections.emptyList() : List.copyOf(builder.channels);
        this.model = builder.model;
        this.device = builder.device;
        this.taskRunner = builder.taskRunner;
        this.preferredOutputType = builder.preferredOutputType;
        this.randomColors = builder.randomColors;
        this.makeMeasurements = builder.makeMeasurements;
        this.optionalArgs.putAll(builder.optionalArgs);
    }

    /**
     * Run inference for the currently selected PathObjects in the current image.
     */
    public InstanSegResults detectObjects() {
        return detectObjects(QP.getCurrentImageData());
    }

    /**
     * Run inference for the currently selected PathObjects in the specified image.
     */
    public InstanSegResults detectObjects(ImageData<BufferedImage> imageData) {
        Objects.requireNonNull(imageData, "No imageData available");
        return detectObjects(imageData, imageData.getHierarchy().getSelectionModel().getSelectedObjects());
    }

    /**
     * Run inference for a collection of PathObjects from the current image.
     */
    public InstanSegResults detectObjects(Collection<? extends PathObject> pathObjects) {
        var imageData = QP.getCurrentImageData();
        var results = runInstanSeg(imageData, pathObjects);
        if (makeMeasurements) {
            for (var pathObject : pathObjects) {
                makeMeasurements(imageData, pathObject.getChildObjects());
            }
        }
        return results;
    }

    /**
     * Run inference for a collection of PathObjects associated with the specified image.
     * @throws IllegalArgumentException if the image or objects are null, or if the objects are not found within the image's hierarchy
     */
    public InstanSegResults detectObjects(ImageData<BufferedImage> imageData, Collection<? extends PathObject> pathObjects)
            throws IllegalArgumentException {
        validateImageAndObjectsOrThrow(imageData, pathObjects);
        var results = runInstanSeg(imageData, pathObjects);
        if (makeMeasurements) {
            var detections = pathObjects.stream().flatMap(p -> p.getChildObjects().stream()).toList();
            makeMeasurements(imageData, detections);
        }
        return results;
    }

    private void validateImageAndObjectsOrThrow(ImageData<BufferedImage> imageData, Collection<? extends PathObject> pathObjects) {
        Objects.requireNonNull(imageData, "No imageData available");
        Objects.requireNonNull(pathObjects, "No objects available");
        // TODO: Consider if there are use cases where it is worthwhile to provide objects that are not in the hierarchy
        var hierarchy = imageData.getHierarchy();
        if (pathObjects.stream().anyMatch(p -> !PathObjectTools.hierarchyContainsObject(hierarchy, p))) {
            throw new IllegalArgumentException("Objects must be contained in the image hierarchy!");
        }
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
    private void makeMeasurements(ImageData<BufferedImage> imageData, Collection<? extends PathObject> detections) {
        double downsample = model.getPreferredDownsample(imageData.getServer().getPixelCalibration());
        DetectionMeasurer.builder()
                .taskRunner(taskRunner)
                .downsample(downsample)
                .build()
                .makeMeasurements(imageData, detections);
    }

    private InstanSegResults runInstanSeg(ImageData<BufferedImage> imageData, Collection<? extends PathObject> pathObjects) {
        long startTime = System.currentTimeMillis();
        Optional<Path> oModelPath = model.getPath();
        if (oModelPath.isEmpty()) {
            return InstanSegResults.emptyInstance();
        }
        Path modelPath = oModelPath.get().resolve("instanseg.pt");

        Optional<List<OutputTensor>> oOutputTensors = this.model.getOutputs();
        if (oOutputTensors.isEmpty()) {
            throw new IllegalArgumentException("No output tensors available even though model is available");
        }
        var outputTensors = oOutputTensors.get();

        // Provide some way to change the number of predictors, even if this can't be specified through the UI
        // See https://forum.image.sc/t/instanseg-under-utilizing-cpu-only-2-3-cores/104496/7
        int nPredictors = Integer.parseInt(System.getProperty("instanseg.numPredictors", "1"));

        // Optionally pad images so that every tile has the required size.
        // This is useful if the model requires a specific input size - but InstanSeg should be able to handle this
        // and inference can be much faster if we permit tiles to be cropped.
        boolean padToInputSize = System.getProperty("instanseg.padToInputSize", "false").strip().equalsIgnoreCase("true");
        if (padToInputSize) {
            logger.warn("Padding to input size is turned on - this is likely to be slower (but could help fix any issues)");
        }
        String layout = "CHW";
        String layoutOutput = "CHW";

        // Get the downsample - this may be specified by the user, or determined from the model spec
        if (!imageData.getServerMetadata().pixelSizeCalibrated()) {
            logger.warn("Running InstanSeg without pixel calibration --- results may not be as expected!");
        }
        double downsample;
        if (this.downsample > 0) {
            downsample = this.downsample;
            logger.debug("Calling InstanSeg with user-specified downsample {}", downsample);
        } else if (!imageData.getServerMetadata().pixelSizeCalibrated()) {
            downsample = 1.0;
            logger.debug("No pixel calibration - defaulting to a downsample of 1.0");
        } else {
            downsample = this.model.getPreferredDownsample(imageData.getServer().getPixelCalibration());
            logger.debug("Calling InstanSeg with calculated downsample {}", downsample);
        }

        // Create an int[] representing a boolean array of channels to use
        boolean[] outputChannelArray = null;
        if (outputChannels != null && outputChannels.length > 0) {
            //noinspection OptionalGetWithoutIsPresent
            outputChannelArray = new boolean[model.getOutputChannels().get()]; // safe to call get because of previous checks
            for (int c : outputChannels) {
                if (c < 0 || c >= outputChannelArray.length) {
                    throw new IllegalArgumentException("Invalid channel index: " + c);
                }
                outputChannelArray[c] = true;
            }
        }

        // If no input channels are specified, use all channels
        var inputChannels = getInputChannels(imageData);

        try (var model = Criteria.builder()
                .setTypes(Mat.class, Mat[].class)
                .optModelUrls(String.valueOf(modelPath.toUri()))
                .optProgress(new ProgressBar())
                .optDevice(device) // Remove this line if devices are problematic!
                .optTranslator(new MatTranslator(layout, layoutOutput, outputChannelArray, optionalArgs))
                .build()
                .loadModel()) {

            BaseNDManager baseManager = (BaseNDManager)model.getNDManager();
            printResourceCount("Resource count before prediction",
                    (BaseNDManager)baseManager.getParentManager());
            baseManager.debugDump(2);
            BlockingQueue<Predictor<Mat, Mat[]>> predictors = new ArrayBlockingQueue<>(nPredictors);

            try {
                for (int i = 0; i < nPredictors; i++) {
                    predictors.put(model.newPredictor());
                }

                printResourceCount("Resource count after creating predictors",
                        (BaseNDManager)baseManager.getParentManager());

                var tiler = createTiler(downsample, tileDims, padding);
                var predictionProcessor = createProcessor(predictors, inputChannels, tileDims, padToInputSize);
                var outputHandler = createOutputHandler(preferredOutputType, randomColors, boundaryThreshold, outputTensors);
                var postProcessor = createPostProcessor(randomColors);
                var processor = new PixelProcessor.Builder<Mat, Mat, Mat[]>()
                        .processor(predictionProcessor)
                        .maskSupplier(OpenCVProcessor.createMatMaskSupplier())
                        .imageSupplier((parameters) -> ImageOps.buildImageDataOp(inputChannels)
                                .apply(parameters.getImageData(), parameters.getRegionRequest()))
                        .tiler(tiler)
                        .outputHandler(outputHandler)
                        .padding((int)Math.round(padding * downsample))
                        .postProcess(postProcessor)
                        .downsample(downsample)
                        .build();

                processor.processObjects(taskRunner, imageData, pathObjects);
                int nObjects = pathObjects.stream().mapToInt(PathObject::nChildObjects).sum();
                if (predictionProcessor instanceof TilePredictionProcessor tileProcessor) {
                    return new InstanSegResults(
                            tileProcessor.getPixelsProcessedCount(),
                            tileProcessor.getTilesProcessedCount(),
                            tileProcessor.getTilesFailedCount(),
                            nObjects,
                            System.currentTimeMillis() - startTime,
                            tileProcessor.wasInterrupted()
                    );
                } else {
                    return InstanSegResults.emptyInstance();
                }
            } finally {
                for (var predictor: predictors) {
                    predictor.close();
                }
                printResourceCount("Resource count after prediction", (BaseNDManager)baseManager.getParentManager());
            }
        } catch (Exception e) {
            logger.error("Error running InstanSeg", e);
            return new InstanSegResults(0, 0, 0, 0,
                    System.currentTimeMillis() - startTime, e instanceof InterruptedException);
        }
    }


    /**
     * Check if we are requesting tiles for debugging purposes.
     * When this is true, we should create objects that represent the tiles - not the objects to be detected.
     * @return Whether the system debugging property is set.
     */
    private static boolean debugTiles() {
        return System.getProperty("instanseg.debug.tiles", "false").strip().equalsIgnoreCase("true");
    }

    private static Processor<Mat, Mat, Mat[]> createProcessor(BlockingQueue<Predictor<Mat, Mat[]>> predictors,
                                                            Collection<? extends ColorTransforms.ColorTransform> inputChannels,
                                                            int tileDims, boolean padToInputSize) {
        if (debugTiles())
            return InstanSeg::createOnes;
        return new TilePredictionProcessor(predictors, inputChannels, tileDims, tileDims, padToInputSize);
    }

    private static Mat[] createOnes(Parameters<Mat, Mat> parameters) {
        var tileRequest = parameters.getTileRequest();
        int width, height;
        if (tileRequest == null) {
            var region = parameters.getRegionRequest();
            width = (int)Math.round(region.getWidth() / region.getDownsample());
            height = (int)Math.round(region.getHeight() / region.getDownsample());
        } else {
            width = tileRequest.getTileWidth();
            height = tileRequest.getTileHeight();
        }
        try (var ones = Mat.ones(height, width, opencv_core.CV_8UC1)) {
            return new Mat[]{ones.asMat()};
        }
    }


    private static OutputHandler<Mat, Mat, Mat[]> createOutputHandler(Class<? extends PathObject> preferredOutputType,
                                                                      boolean randomColors,
                                                                      int boundaryThreshold,
                                                                      List<OutputTensor> outputTensors) {
        // TODO: Reinstate this for Mat[] output (it was written for Mat output)
//        if (debugTiles())
//            return OutputHandler.createUnmaskedObjectOutputHandler(OpenCVProcessor.createAnnotationConverter());
        var converter = new InstanSegOutputToObjectConverter(outputTensors, preferredOutputType);
        if (boundaryThreshold >= 0) {
            return new PruneObjectOutputHandler<>(converter, boundaryThreshold);
        } else {
            return OutputHandler.createObjectOutputHandler(converter);
        }
    }

    private static Tiler createTiler(double downsample, int tileDims, int padding) {
        int sizeWithoutPadding = (int) Math.ceil(downsample * (tileDims - (double) padding*2));
        return Tiler.builder(sizeWithoutPadding)
                .alignCenter()
                .cropTiles(false)
                .build();
    }


    /**
     * Get the input channels to use; if we don't have any specified, use all of them
     * @param imageData The image data
     * @return The possible input channels.
     */
    private List<ColorTransforms.ColorTransform> getInputChannels(ImageData<BufferedImage> imageData) {
        if (inputChannels == null || inputChannels.isEmpty()) {
            List<ColorTransforms.ColorTransform> channels = new ArrayList<>();
            for (int i = 0; i < imageData.getServer().nChannels(); i++) {
                channels.add(ColorTransforms.createChannelExtractor(i));
            }
            return channels;
        } else {
            return inputChannels;
        }
    }

    private static ObjectProcessor createPostProcessor(boolean randomColors) {
        if (debugTiles())
            return null;
        var merger = ObjectMerger.createIoMinMerger(0.5, MeasurementStrategy.MEAN);
        var fixer = OverlapFixer.builder()
                .clipOverlaps()
                .keepFragments(false)
                .sortBySolidity()
                .build();
        return merger.andThen(fixer).andThen(input -> {
            if (randomColors) {
                PathObjectTools.setRandomColors(input, new Random(input.size()));
            }
            return input.stream().map(p -> (PathObject)p).toList();
        });
    }

    /**
     * Assign a random color to a PathObject and all descendants, returning the object.
     *
     * @param pathObject The PathObject
     * @param rng A random number generator.
     */
    private static PathObject assignRandomColor(PathObject pathObject, Random rng) {
        pathObject.setColor(randomRGB(rng));
        for (var child : pathObject.getChildObjects()) {
            assignRandomColor(child, rng);
        }
        return pathObject;
    }

    private static int randomRGB(Random rng) {
        return ColorTools.packRGB(rng.nextInt(255), rng.nextInt(255), rng.nextInt(255));
    }

    /**
     * Print resource count for debugging purposes.
     * If we are not logging at debug level, do nothing.
     * @param title The name to be used in the log.
     * @param manager The NDManager to print from.
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
        private static final int MAX_TILE_DIMS = 4096;

        private int tileDims = 512;
        private double downsample = -1; // Optional - we usually get this from the model
        private int padding = 80; // Previous default of 40 could miss large objects
        private int[] outputChannels = null;
        private boolean randomColors = true;
        private boolean makeMeasurements = false;
        private Device device = Device.fromName("cpu");
        private TaskRunner taskRunner = TaskRunnerUtils.getDefaultInstance().createTaskRunner();
        private Collection<? extends ColorTransforms.ColorTransform> channels;
        private InstanSegModel model;
        private Class<? extends PathObject> preferredOutputType;
        private final Map<String, Object> optionalArgs = new LinkedHashMap<>();

        Builder() {}

        /**
         * Set the width and height of tiles
         * @param tileDims The tile width and height
         * @return this builder
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
         * @return this builder
         */
        public Builder downsample(double downsample) {
            this.downsample = downsample;
            return this;
        }

        /**
         * Set the padding (overlap) between tiles
         * @param padding The extra size added to tiles to allow overlap
         * @return this builder
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
         * Set the output channels to be retained.
         * <p>
         * For a 2-channel model with cell outputs, use 0 for nuclei and 1 for full cell, or [0, 1] for both.
         * <p>
         * These values are converted and passed to InstanSeg, which can optimize the code path accordingly -
         * so it can be much cheaper to eliminate channels now, rather than discard unwanted detections later.
         *
         * @param outputChannels 0-based indices of the output channels, or leave empty to use all channels
         * @return this builder
         */
        public Builder outputChannels(int... outputChannels) {
            this.outputChannels = outputChannels.clone();
            return this;
        }

        /**
         * Set the channels to be used in inference
         * @param channels A collection of channels to be used in inference
         * @return this builder
         */
        public Builder inputChannels(Collection<? extends ColorTransforms.ColorTransform> channels) {
            this.channels = channels;
            return this;
        }

        /**
         * Set the channels to be used in inference
         * @param channels Channels to be used in inference
         * @return this builder
         */
        public Builder inputChannels(ColorTransforms.ColorTransform channel, ColorTransforms.ColorTransform... channels) {
            var l = new ArrayList<ColorTransforms.ColorTransform>();
            l.add(channel);
            l.addAll(Arrays.asList(channels));
            this.channels = l;
            return this;
        }

        /**
         * Request that all input channels be used in inference
         * @return this builder
         */
        public Builder allInputChannels() {
            this.channels = Collections.emptyList();
            return this;
        }

        /**
         * Set the channels using indices
         * @param channels Integers used to specify the channels used
         * @return this builder
         */
        public Builder inputChannelIndices(Collection<Integer> channels) {
            this.channels = channels.stream()
                    .map(ColorTransforms::createChannelExtractor)
                    .toList();
            return this;
        }

        /**
         * Set the channels using indices
         * @param channels Integers used to specify the channels used
         * @return this builder
         */
        public Builder inputChannels(int channel, int... channels) {
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
         * @return this builder
         */
        public Builder inputChannelNames(Collection<String> channels) {
            this.channels = channels.stream()
                    .map(ColorTransforms::createChannelExtractor)
                    .toList();
            return this;
        }

        /**
         * Set the channel names to be used
         * @param channels A set of channel names
         * @return this builder
         */
        public Builder inputChannelNames(String channel, String... channels) {
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
         * @return this builder
         */
        public Builder randomColors() {
            return randomColors(true);
        }

        /**
         * Optionally request that random colors be used for the output objects.
         * @param doRandomColors Whether to use random colors for output object.
         * @return this builder
         */
        public Builder randomColors(boolean doRandomColors) {
            this.randomColors = doRandomColors;
            return this;
        }

        /**
         * Set the number of threads used
         * @param nThreads The number of threads to be used
         * @return this builder
         */
        public Builder nThreads(int nThreads) {
            this.taskRunner = TaskRunnerUtils.getDefaultInstance().createTaskRunner(nThreads);
            return this;
        }

        /**
         * Set the TaskRunner
         * @param taskRunner An object that will run tasks and show progress
         * @return this builder
         */
        public Builder taskRunner(TaskRunner taskRunner) {
            this.taskRunner = taskRunner;
            return this;
        }

        /**
         * Set the specific model to be used
         * @param model An already instantiated InstanSeg model.
         * @return this builder
         */
        public Builder model(InstanSegModel model) {
            this.model = model;
            return this;
        }

        /**
         * Set the specific model by path
         * @param path A path on disk to create an InstanSeg model from.
         * @return this builder
         */
        public Builder modelPath(Path path) throws IOException {
            return model(InstanSegModel.fromPath(path));
        }

        /**
         * Set the specific model by path
         * @param path A path on disk to create an InstanSeg model from.
         * @return this builder
         */
        public Builder modelPath(String path) throws IOException {
            return modelPath(Path.of(path));
        }

        /**
         * Set the device to be used
         * @param deviceName The name of the device to be used (eg, "gpu", "mps").
         * @return this builder
         */
        public Builder device(String deviceName) {
            this.device = Device.fromName(deviceName);
            return this;
        }

        /**
         * Set the device to be used
         * @param device The {@link Device} to be used
         * @return this builder
         */
        public Builder device(Device device) {
            this.device = device;
            return this;
        }

        /**
         * Specify cells as the output class, possibly without nuclei
         * @return this builder
         */
        public Builder outputCells() {
            this.preferredOutputType = PathCellObject.class;
            return this;
        }

        /**
         * Specify (possibly nested) detections as the output class
         * @return this builder
         */
        public Builder outputDetections() {
            this.preferredOutputType = PathDetectionObject.class;
            return this;
        }

        /**
         * Specify (possibly nested) annotations as the output class
         * @return this builder
         */
        public Builder outputAnnotations() {
            this.preferredOutputType = PathAnnotationObject.class;
            return this;
        }

        /**
         * Output whatever the default object type for the model is: probably cells for nuclei + cell models, nuclei for everything else.
         * @return this builder
         */
        private Builder outputDefault() {
            this.preferredOutputType = null;
            return this;
        }


        /**
         * Set the output type based on a string value.
         * @param outputType the type of output (usually cell, annotation or detection)
         * @return this builder.
         */
        public Builder outputType(String outputType) {
            switch(OutputType.fromString(outputType)) {
                case CELL -> {
                    return this.outputCells();
                }
                case DETECTION -> {
                    return this.outputDetections();
                }
                case ANNOTATION -> {
                    return this.outputAnnotations();
                }
                case DEFAULT -> {
                    return this.outputDefault();
                }
                default -> throw new IllegalArgumentException("Unknown output type");
            }
        }

        /**
         * Set a number of optional arguments
         * @param optionalArgs The argument names and values.
         * @return A modified builder.
         */
        public Builder args(Map<String, ?> optionalArgs) {
            this.optionalArgs.putAll(optionalArgs);
            return this;
        }

        /**
         * Set a number of optional arguments
         * @param key The argument name
         * @param value The argument value
         * @return A modified builder.
         */
        public Builder arg(String key, Object value) {
            this.optionalArgs.put(key, value);
            return this;
        }

        /**
         * Request to make measurements from the objects created by InstanSeg.
         * @return this builder
         */
        public Builder makeMeasurements(boolean doMeasure) {
            this.makeMeasurements = doMeasure;
            return this;
        }

        /**
         * Build the InstanSeg instance.
         * @return An InstanSeg instance ready for object detection.
         */
        public InstanSeg build() {
            return new InstanSeg(this);
        }

    }

    /**
     * Possible output types for InstanSeg
     */
    public enum OutputType {
        /**
         * Output possibly nested annotations
         */
        ANNOTATION,
        /**
         * Output possibly nested detections
         */
        DETECTION,
        /**
         * Output possibly cells that may or may not have a nucleus
         */
        CELL,
        /**
         * Whatever the default model behaviour is.
         */
        DEFAULT;

        /**
         * Fetch the output type matching a string value.
         * @param outputType the string input, possibly containing trailing whitespace; can be plural
         * @return the matching output type, if we can find it; otherwise an exception
         */
        public static OutputType fromString(String outputType) {
            outputType = outputType.strip();
            if (outputType.endsWith("s")) {
                outputType = outputType.substring(0, outputType.length() - 1);
            }
            for (var type: values()) {
                if (type.toString().equalsIgnoreCase(outputType)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown output type: " + outputType);
        }
    }

}
