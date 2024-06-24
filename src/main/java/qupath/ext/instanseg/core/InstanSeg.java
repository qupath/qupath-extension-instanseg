package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.repository.zoo.ModelNotFoundException;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.TaskRunnerUtils;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.IntStream;

public class InstanSeg {
    private int tileDims;
    private double downsample;
    private int padding;
    private int boundary;
    private int numOutputChannels;
    private ImageData<BufferedImage> imageData;
    private Collection<ColorTransforms.ColorTransform> channels;
    private TaskRunner taskRunner;
    private InstanSegModel model;
    private Device device;

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
                taskRunner
        );
    }

    public static final class Builder {
        private int tileDims = 512;
        private double downsample = 1;
        private int padding = 40;
        private int boundary = 20;
        private int numOutputChannels = 2;
        private ImageData<BufferedImage> imageData = QP.getCurrentImageData();
        // todo: set default?
        private Collection<ColorTransforms.ColorTransform> channels;
        private TaskRunner taskRunner = TaskRunnerUtils.getDefaultInstance().createTaskRunner();
        private Device device;
        private InstanSegModel model;

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

        public Builder taskRunner(TaskRunner taskRunner) {
            this.taskRunner = taskRunner;
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

        public Builder device(String device) {
            this.device = Device.fromName(device);
            return this;
        }

        public InstanSeg build() {
            InstanSeg instanSeg = new InstanSeg();
            instanSeg.channels = this.channels;
            instanSeg.taskRunner = this.taskRunner;
            instanSeg.numOutputChannels = this.numOutputChannels;
            instanSeg.tileDims = this.tileDims;
            instanSeg.boundary = this.boundary;
            instanSeg.model = this.model;
            instanSeg.padding = this.padding;
            instanSeg.downsample = this.downsample;
            instanSeg.imageData = this.imageData;
            instanSeg.device = this.device;
            return instanSeg;
        }
    }
}
