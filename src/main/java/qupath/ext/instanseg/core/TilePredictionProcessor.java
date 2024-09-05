package qupath.ext.instanseg.core;

import ai.djl.inference.Predictor;
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
import qupath.lib.regions.Padding;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class TilePredictionProcessor implements Processor<Mat, Mat, Mat> {

    private static final Logger logger = LoggerFactory.getLogger(TilePredictionProcessor.class);

    private final BlockingQueue<Predictor<Mat, Mat>> predictors;

    private final int inputWidth;
    private final int inputHeight;
    private final boolean doPadding;
    private final Collection<ColorTransforms.ColorTransform> channels;

    private final double lowPercentile = 0.1;
    private final double highPercentile = 99.9;

    private final AtomicLong nPixelsProcessed = new AtomicLong(0);
    private final AtomicInteger nTilesProcessed = new AtomicInteger(0);
    private final AtomicInteger nTilesFailed = new AtomicInteger(0);

    /**
     * Cache normalization op so it doesn't need to be recalculated for every tile.
     * Note that this assumes we don't reuse the TilePredictionProcessor for multiple images that contain
     * the exact same ROI.
     * It may be possible to break this rule, but you'd really have to try hard.
     */
    private final Map<ROI, ImageOp> normalization = Collections.synchronizedMap(new WeakHashMap<>());

    TilePredictionProcessor(BlockingQueue<Predictor<Mat, Mat>> predictors,
                            Collection<? extends ColorTransforms.ColorTransform> channels,
                            int inputWidth, int inputHeight, boolean doPadding) {
        this.predictors = predictors;
        this.channels = List.copyOf(channels);
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.doPadding = doPadding;
    }

    /**
     * get the total number of tiles that were processed, including any that failed.
     * @return the number of tiles that were processed
     */
    public int getTilesProcessedCount() {
        return nTilesProcessed.get();
    }

    /**
     * Get the number of tiles that threw an exception during processing.
     * @return the number of tiles that failed
     */
    public int getTilesFailedCount() {
        return nTilesFailed.get();
    }

    /**
     * Get the number of pixels that were processed.
     * This is calculated by summing the width x height of each tile that was processed.
     * The number of channels does not influence the result.
     * <p>
     * One use of this is to help assess the impact of padding on the processing time.
     * @return the pixels that were processed
     */
    public long getPixelsProcessedCount() {
        return nPixelsProcessed.get();
    }

    @Override
    public Mat process(Parameters<Mat, Mat> params) throws IOException {

        var mat = params.getImage();

        var imageData = params.getImageData();

        // Normalize using percentiles (from a sufficiently low-resolution image)
        ImageOp norm = normalization.computeIfAbsent(params.getParent().getROI(),
                roi -> getNormalization(imageData, roi, channels, lowPercentile, highPercentile));

        // Number of pixels in the Mat *excluding channels*
        long nPixels = mat.total();

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
            logger.debug("Predicting tile {}", mat);
            var matOutput = predictor.predict(mat);

            matOutput.convertTo(matOutput, opencv_core.CV_32S);
            if (padding != null)
                matOutput = OpenCVTools.crop(matOutput, padding);
            return matOutput;
        } catch (TranslateException e) {
            nTilesFailed.incrementAndGet();
            logger.error("Error in prediction", e);
        } catch (InterruptedException | IllegalStateException e) {
            // illegal state exception comes when ndmanager is closed from another thread (I think)
            nTilesFailed.incrementAndGet();
            logger.debug("Prediction interrupted", e);
        } finally {
            if (predictor != null) {
                try {
                    predictors.put(predictor);
                } catch (InterruptedException e) {
                    logger.warn("Tiling interrupted");
                }
            }
            nTilesProcessed.incrementAndGet();
            nPixelsProcessed.addAndGet(nPixels);
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
     * @param roi The ROI defining the region used for normalization.
     * @param channels The channels/color transforms that the segmentation
     *                 will be restricted to.
     * @param lowPerc The lower percentile to use in normalisation.
     * @param highPerc The upper percentile to use in normalisation.
     * @return Percentile-based normalisation based on the bounding box,
     * or default tile-based percentile normalisation if that fails.
     */
    private static ImageOp getNormalization(ImageData<BufferedImage> imageData, ROI roi, Collection<ColorTransforms.ColorTransform> channels, double lowPerc, double highPerc) {
        var defaults = ImageOps.Normalize.percentile(lowPerc, highPerc, true, 1e-6);
        try {
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
