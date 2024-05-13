package qupath.ext.instanseg.core;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.TranslateException;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.awt.common.BufferedImageTools;
import qupath.lib.experimental.pixels.MeasurementProcessor;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.experimental.pixels.Processor;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.PixelType;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.Padding;
import qupath.lib.regions.RegionRequest;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;

class TilePredictionProcessor implements Processor<Mat, Mat, Mat> {
    private static final Logger logger = LoggerFactory.getLogger(TilePredictionProcessor.class);

    private final BlockingQueue<Predictor<Mat, Mat>> predictors;

    private final NDManager manager;
    private final String layout;
    private final String layoutOutput;
    private final int inputWidth;
    private final int inputHeight;
    private final boolean doPadding;
    private final Collection<ColorTransforms.ColorTransform> channels;

    TilePredictionProcessor(BlockingQueue<Predictor<Mat, Mat>> predictors, NDManager manager,
                            String layout, String layoutOutput, Collection<ColorTransforms.ColorTransform> channels,
                            int inputWidth, int inputHeight, boolean doPadding) {
        this.predictors = predictors;
        this.manager = manager;
        this.layout = layout;
        this.layoutOutput = layoutOutput;
        this.channels = channels;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.doPadding = doPadding;
    }

    @Override
    public Mat process(Parameters<Mat, Mat> params) throws IOException {

        var mat = params.getImage();

        ImageOp norm = ImageOps.Normalize.percentile(1, 99);
        var imageData = params.getImageData();
        if (imageData.isFluorescence()) {
            norm = getNormalization(imageData, params.getParent(), channels, 0.1, 99.9);
        }
        var preprocessing = ImageOps.Core.sequential(
                ImageOps.Core.ensureType(PixelType.FLOAT32),
                norm,
                ImageOps.Core.clip(-0.5, 1.5)
        );
        mat = preprocessing.apply(mat);

        Padding padding = null;
        if (doPadding && inputHeight > 0 && inputWidth > 0 && (mat.rows() < inputHeight || mat.cols() < inputWidth)) {
            padding = Padding.getPadding(0, Math.max(0, inputWidth - mat.cols()), 0, Math.max(0, inputHeight - mat.rows()));
            var mat2 = new Mat();
            opencv_core.copyMakeBorder(mat, mat2, padding.getY1(), padding.getY2(), padding.getX1(), padding.getX2(), opencv_core.BORDER_REFLECT101);
            mat = mat2;
        }

        Predictor<Mat, Mat> predictor = null;
        try {
            predictor = predictors.take();
            var matOutput = predictor.predict(mat);

            matOutput.convertTo(matOutput, opencv_core.CV_32S);
            if (padding != null)
                matOutput = OpenCVTools.crop(matOutput, padding);
            return matOutput;
        } catch (TranslateException e) {
            logger.error("Error in prediction", e);
        } catch (InterruptedException | IllegalStateException e) {
            // illegal state exception comes when ndmanager is closed from another thread
            logger.debug("Prediction interrupted", e);
        } finally {
            if (predictor != null) {
                try {
                    predictors.put(predictor);
                } catch (InterruptedException e) {
                    logger.warn("Tiling interrupted");
                }
            }
        }
        return null;
    }

    /**
     * Try to fetch percentile normalisation factors from the image, using a
     * large downsample if the input pathObject is large. Uses the
     * bounding box of the pathObject so hopefully allows comparable output
     * to the same image through InstanSeg in Python as a full image.
     *
     * @param imageData  ImageData for the current image.
     * @param pathObject The object that we'll be doing segmentation in.
     * @param channels The channels/color transforms that the segmentation
     *                 will be restricted to.
     * @param lowPerc The lower percentile to use in normalisation.
     * @param highPerc The upper percentile to use in normalisation.
     * @return Percentile-based normalisation based on the bounding box,
     * or default tile-based percentile normalisation if that fails.
     */
    private static ImageOp getNormalization(ImageData<BufferedImage> imageData, PathObject pathObject, Collection<ColorTransforms.ColorTransform> channels, double lowPerc, double highPerc) {
        var defaults = ImageOps.Normalize.percentile(lowPerc, highPerc, true, 1e-6);
        try {
            // read the bounding box of the current object
            var roi = pathObject.getROI();

            BufferedImage image;
            double downsample = Math.max(1,  Math.max(roi.getBoundsWidth(), roi.getBoundsHeight()) / 1024);
            var request = RegionRequest.createInstance(imageData.getServerPath(), downsample, roi);
            image = imageData.getServer().readRegion(request);
            double eps = 1e-6;

            var params = channels.stream().map(colorTransform -> {
                var mask = BufferedImageTools.createROIMask(image.getWidth(), image.getHeight(), roi, request);
                float[] maskPix = ColorTransforms.createChannelExtractor(0).extractChannel(null, mask, null);
                float[] fpix = colorTransform.extractChannel(imageData.getServer(), image, null);
                assert maskPix.length == fpix.length;

                int ind = 0;
                for (int i = 0; i< maskPix.length; i++) {
                    if (maskPix[i] == 255) {
                        fpix[ind] = fpix[i];
                        ind++;
                    }
                }
                double[] usePixels = new double[ind];
                for (int i = 0; i < ind; i++) {
                    usePixels[i] = fpix[i];
                }

                double offset;
                double scale;
                var lo = MeasurementProcessor.Functions.percentile(lowPerc).apply(usePixels);
                var hi = MeasurementProcessor.Functions.percentile(highPerc).apply(usePixels);
                scale = 1.0 / (hi - lo + eps);
                offset = -lo * scale;
                return new double[]{offset, scale};
            }).toList();

            return ImageOps.Core.sequential(
                    ImageOps.Core.multiply(params.stream().mapToDouble(e -> e[1]).toArray()),
                    ImageOps.Core.add(params.stream().mapToDouble(e -> e[0]).toArray())
            );

        } catch (Exception e) {
            logger.error("Error reading thumbnail", e);
        }
        return defaults;
    }

}
