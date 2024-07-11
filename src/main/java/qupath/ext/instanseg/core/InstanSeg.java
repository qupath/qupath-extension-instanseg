package qupath.ext.instanseg.core;

import ai.djl.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.TaskRunnerUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

public class InstanSeg {
    private static final Logger logger = LoggerFactory.getLogger(InstanSeg.class);

    private final int tileDims;
    private final double downsample;
    private final int padding;
    private final int boundary;
    private final int numOutputChannels;
    private final ImageData<BufferedImage> imageData;
    private final Collection<ColorTransforms.ColorTransform> channels;
    private final InstanSegModel model;
    private final Device device;
    private final TaskRunner taskRunner;


    /**
     * Create a builder object for InstanSeg.
     * @return A builder, which may not be valid.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Run inference for the currently selected PathObjects.
     */
    public void detectObjects() {
        detectObjects(imageData.getHierarchy().getSelectionModel().getSelectedObjects());
    }

    /**
     * Run inference for a collection of PathObjects.
     */
    public void detectObjects(Collection<PathObject> pathObjects) {
        model.runInstanSeg(
                imageData,
                pathObjects,
                channels,
                tileDims,
                downsample,
                padding,
                boundary,
                device,
                numOutputChannels == 1,
                taskRunner
        );
    }


    /**
     * A builder class for InstanSeg.
     */
    public static final class Builder {
        private int tileDims = 512;
        private double downsample = 1;
        private int padding = 40;
        private int boundary = 20;
        private int numOutputChannels = 2;
        private Device device = Device.fromName("cpu");
        private TaskRunner taskRunner = TaskRunnerUtils.getDefaultInstance().createTaskRunner();
        private ImageData<BufferedImage> imageData;
        private Collection<ColorTransforms.ColorTransform> channels;
        private InstanSegModel model;

        Builder() {}

        /**
         * Set the width and height of tiles
         * @param tileDims The tile width and height
         * @return A modified builder
         */
        public Builder tileDims(int tileDims) {
            this.tileDims = tileDims;
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
            this.padding = padding;
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
            return channelIndices(
                    IntStream.of(imageData.getServer().nChannels())
                            .boxed()
                            .toList());
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
         * Build the InstanSeg instance.
         * @return An InstanSeg instance ready for object detection.
         */
        public InstanSeg build() {
            if (imageData == null) {
                throw new IllegalStateException("imageData cannot be null!");
            }
            if (channels == null) {
                 allChannels();
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
                    this.taskRunner);
        }

    }

    private InstanSeg(int tileDims, double downsample, int padding, int boundary, int numOutputChannels, ImageData<BufferedImage> imageData,
                      Collection<ColorTransforms.ColorTransform> channels, InstanSegModel model, Device device, TaskRunner taskRunner) {
        this.tileDims = tileDims;
        this.downsample = downsample;
        this.padding = padding;
        this.boundary = boundary;
        this.numOutputChannels = numOutputChannels;
        this.imageData = imageData;
        this.channels = channels;
        this.model = model;
        this.device = device;
        this.taskRunner = taskRunner;
    }
}
