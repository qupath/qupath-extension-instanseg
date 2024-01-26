package qupath.ext.instanseg.core;

import ai.djl.ndarray.NDList;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.ext.djl.DjlTools;


class MatTranslator implements Translator<Mat, Mat> {

    private String inputLayoutNd, outputLayoutNd;

    public MatTranslator(String inputLayoutNd, String outputLayoutNd) {
        this.inputLayoutNd = inputLayoutNd;
        this.outputLayoutNd = outputLayoutNd;
    }

    /**
     * Convert Mat to NDArray and add to an NDList.
     * Note that not all OpenCV types are supported.
     * Specifically, 16-bit types should be avoided.
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Mat input) throws Exception {
        var ndarray = DjlTools.matToNDArray(ctx.getNDManager(), input, inputLayoutNd);
        return new NDList(ndarray);
    }

    @Override
    public Mat processOutput(TranslatorContext ctx, NDList list) throws Exception {
        var array = list.get(0);
        return DjlTools.ndArrayToMat(array, outputLayoutNd);
    }

}
