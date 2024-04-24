package qupath.ext.instanseg.core;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.experimental.pixels.MeasurementProcessor;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.utils.ObjectMerger;
import qupath.lib.regions.RegionRequest;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.stream.DoubleStream;

public class InstanSegUtils {
    private static final Logger logger = LoggerFactory.getLogger(InstanSegUtils.class);

    private InstanSegUtils() {
        throw new AssertionError("Do not instantiate this class");
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
     * @return Percentile-based normalisation based on the bounding box,
     * or default tile-based percentile normalisation if that fails.
     */
    static ImageOp getNormalization(ImageData<BufferedImage> imageData, PathObject pathObject, List<ColorTransforms.ColorTransform> channels) {
        var defaults = ImageOps.Normalize.percentile(1, 99, true, 1e-6);
        // this is just a reimplementation of percentile norm using the untiled
        // bounding box at the lowest downsample we can get while being less
        // than 1M pixels
        try {
            // read the bounding box of the current object
            var roi = pathObject.getROI();
            double nPix = roi.getBoundsWidth() * roi.getBoundsHeight();
            // if larger than max allowed size, then downsample... I think?
            double downsample = Math.max(nPix / 1e7, 1);
            var request = RegionRequest.createInstance(imageData.getServerPath(), downsample, roi);
            var image = imageData.getServer().readRegion(request);

            double eps = 1e-6;
            double[] offsets = new double[channels.size()];
            double[] scales = new double[channels.size()];

            for (int i = 0; i < channels.size(); i++) {
                var channel = channels.get(i);
                float[] fpix = channel.extractChannel(imageData.getServer(), image, null);
                double[] pixels = new double[fpix.length];
                for (int j = 0; j < pixels.length; j++) {
                    pixels[j] = (double)fpix[j];
                }
                var lo = MeasurementProcessor.Functions.percentile(1).apply(pixels);
                var hi = MeasurementProcessor.Functions.percentile(99).apply(pixels);
                if (hi == lo && eps == 0.0) {
                    logger.warn("Normalization percentiles give the same value ({}), scale will be Infinity", lo);
                    scales[i] = Double.POSITIVE_INFINITY;
                } else {
                    scales[i] = 1.0 / (hi - lo + eps);
                }
                offsets[i] = -lo * scales[i];
            }
            return ImageOps.Core.sequential(
                    ImageOps.Core.multiply(scales),
                    ImageOps.Core.add(offsets)
            );

        } catch (Exception e) {
            logger.error("Error reading thumbnail", e);
        }
        return defaults;
    }

}
