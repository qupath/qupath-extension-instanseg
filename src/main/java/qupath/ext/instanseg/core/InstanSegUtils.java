package qupath.ext.instanseg.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.experimental.pixels.MeasurementProcessor;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.objects.PathObject;
import qupath.lib.regions.RegionRequest;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.util.List;

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


            BufferedImage image;
            if (imageData.getServer().nResolutions() > 1) {
                // if there's more than one resolution, pray that the thumbnail is reasonable size
                image = imageData.getServer().getDefaultThumbnail(0, 0);
            } else {
                double downsample = Math.max(nPix / 5e7, 1);
                var request = RegionRequest.createInstance(imageData.getServerPath(), downsample, roi);
                image = imageData.getServer().readRegion(request);
            }

            double eps = 1e-6;
            var params = channels.stream().map(colorTransform -> {
                float[] fpix = colorTransform.extractChannel(imageData.getServer(), image, null);
                double[] pixels = new double[fpix.length];
                double offset;
                double scale;
                for (int j = 0; j < pixels.length; j++) {
                    pixels[j] = fpix[j];
                }
                var lo = MeasurementProcessor.Functions.percentile(1).apply(pixels);
                var hi = MeasurementProcessor.Functions.percentile(99).apply(pixels);
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
