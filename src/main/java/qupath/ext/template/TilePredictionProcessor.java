package qupath.ext.template;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.TranslateException;
import org.bytedeco.opencv.global.opencv_core;
import qupath.lib.experimental.pixels.Processor;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.regions.Padding;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.tools.OpenCVTools;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;

class TilePredictionProcessor implements Processor<Mat, Mat, Mat> {

    private final BlockingQueue<Predictor<Mat, Mat>> predictors;

    private final NDManager manager;
    private final ImageOp preprocessing;
    private final String layout;
    private final String layoutOutput;
    private final int inputWidth;
    private final int inputHeight;
    private final boolean doPadding;

    TilePredictionProcessor(BlockingQueue<Predictor<Mat, Mat>> predictors, NDManager manager,
                            String layout, String layoutOutput, ImageOp preprocessing,
                            int inputWidth, int inputHeight, boolean doPadding) {
        this.predictors = predictors;
        this.manager = manager;
        this.layout = layout;
        this.layoutOutput = layoutOutput;
        this.preprocessing = preprocessing;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.doPadding = doPadding;
    }

    @Override
    public Mat process(Parameters<Mat, Mat> params) throws IOException, InterruptedException {

        var mat = params.getImage();
        mat = preprocessing.apply(mat);
        Padding padding = null;
//        OpenCVTools.matToImagePlus(params.getRegionRequest().toString(), mat).show()
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

            // getLogger().info("Mat: {}", mat)
            matOutput.convertTo(matOutput, opencv_core.CV_32S);
            // OpenCVTools.matToImagePlus("Before", matOutput).show()
            if (padding != null)
                matOutput = OpenCVTools.crop(matOutput, padding);
                // OpenCVTools.matToImagePlus("After", matOutput).show()
            return matOutput;
        } catch (TranslateException | InterruptedException e) {
            // todo: deal with exception
            throw new RuntimeException(e);
        } finally {
            if (predictor != null)
                predictors.put(predictor);
        }
    }
}
