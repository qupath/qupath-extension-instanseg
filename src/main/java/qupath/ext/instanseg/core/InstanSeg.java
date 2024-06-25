package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.repository.zoo.ModelNotFoundException;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.IntStream;

public class InstanSeg {
    private final int tileDims;
    private final double downsample;
    private final int padding;
    private final int boundary;
    private final int numOutputChannels;
    private final ImageData<BufferedImage> imageData;
    private final Collection<ColorTransforms.ColorTransform> channels;
    private final int nThreads;
    private final InstanSegModel model;
    private final Device device;


    public static Builder builder() {
        return new Builder();
    }

    public void detectObjects() throws ModelNotFoundException, MalformedModelException, IOException, InterruptedException {
        model.runInstanSeg(
                QP.getSelectedObjects(),
                imageData,
                channels,
                tileDims,
                downsample,
                padding,
                boundary,
                device,
                numOutputChannels == 1,
                QPEx.createTaskRunner(nThreads)
        );
    }

    public static final class Builder {
        private int tileDims = 512;
        private double downsample = 1;
        private int padding = 40;
        private int boundary = 20;
        private int numOutputChannels = 2;
        private ImageData<BufferedImage> imageData = QP.getCurrentImageData();
        private Collection<ColorTransforms.ColorTransform> channels;
        private Device device;
        private InstanSegModel model;
        private int nThreads = 1;

        Builder() {}

        public Builder tileDims(int tileDims) {
            this.tileDims = tileDims;
            return this;
        }

        public Builder downsample(double downsample) {
            this.downsample = downsample;
            return this;
        }

        public Builder interTilePadding(int padding) {
            this.padding = padding;
            return this;
        }

        public Builder tileBoundary(int boundary) {
            this.boundary = boundary;
            return this;
        }

        public Builder numOutputChannels(int numOutputChannels) {
            this.numOutputChannels = numOutputChannels;
            return this;
        }

        public Builder imageData(ImageData<BufferedImage> imageData) {
            this.imageData = imageData;
            return this;
        }

        public Builder currentImageData() {
            this.imageData = QP.getCurrentImageData();
            return this;
        }

        public Builder channels(Collection<ColorTransforms.ColorTransform> channels) {
            this.channels = channels;
            return this;
        }

        public Builder allChannels() {
            // todo: lazy eval this?
            return channelIndices(IntStream.of(imageData.getServer().nChannels()).boxed().toList());
        }

        public Builder channelIndices(Collection<Integer> channels) {
            this.channels = channels.stream()
                    .map(ColorTransforms::createChannelExtractor)
                    .toList();
            return this;
        }

        public Builder channelNames(Collection<String> channels) {
            this.channels = channels.stream()
                    .map(ColorTransforms::createChannelExtractor)
                    .toList();
            return this;
        }

        public Builder nThreads(int nThreads) {
            this.nThreads = nThreads;
            return this;
        }

        public Builder model(InstanSegModel model) {
            this.model = model;
            return this;
        }

        public Builder modelPath(Path path) throws IOException {
            return model(InstanSegModel.fromPath(path));
        }

        public Builder modelName(String name) {
            return model(InstanSegModel.fromName(name));
        }

        public Builder device(String deviceName) {
            this.device = Device.fromName(deviceName);
            return this;
        }

        public Builder device(Device device) {
            this.device = device;
            return this;
        }

        public InstanSeg build() {
            if (imageData == null) {
                this.currentImageData();
            }
            if (channels == null) {
                 allChannels();
            }
            if (device == null) {
                device("cpu");
            }
            return new InstanSeg(
                    this.tileDims,
                    this.downsample,
                    this.padding,
                    this.boundary,
                    this.numOutputChannels,
                    this.imageData,
                    this.channels,
                    this.nThreads,
                    this.model,
                    this.device
            );
        }

    }

    private InstanSeg(int tileDims, double downsample, int padding, int boundary, int numOutputChannels, ImageData<BufferedImage> imageData,
                      Collection<ColorTransforms.ColorTransform> channels, int nThreads, InstanSegModel model, Device device) {
        this.tileDims = tileDims;
        this.downsample = downsample;
        this.padding = padding;
        this.boundary = boundary;
        this.numOutputChannels = numOutputChannels;
        this.imageData = imageData;
        this.channels = channels;
        this.nThreads = nThreads;
        this.model = model;
        this.device = device;
    }
}
