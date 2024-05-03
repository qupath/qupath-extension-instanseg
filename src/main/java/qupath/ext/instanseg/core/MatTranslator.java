package qupath.ext.instanseg.core;

import ai.djl.Device;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.ext.djl.DjlTools;


class MatTranslator implements Translator<Mat, Mat> {

    private String inputLayoutNd, outputLayoutNd;
    private boolean nucleiOnly = false;

    public MatTranslator(String inputLayoutNd, String outputLayoutNd, boolean nucleiOnly) {
        this.inputLayoutNd = inputLayoutNd;
        this.outputLayoutNd = outputLayoutNd;
        this.nucleiOnly = nucleiOnly;
    }

    /**
     * Convert Mat to NDArray and add to an NDList.
     * Note that not all OpenCV types are supported.
     * Specifically, 16-bit types should be avoided.
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Mat input) throws Exception {
        var manager = ctx.getNDManager();
        var ndarray = DjlTools.matToNDArray(manager, input, inputLayoutNd);
        var out = new NDList(ndarray);
        var inds = new int[]{1, 1};
        if (nucleiOnly) {
            inds[1] = 0;
        }
        var array = manager.create(inds, new Shape(2));
        var arrayCPU = array.toDevice(Device.cpu(), false);
        out.add(arrayCPU);
        return out;
    }

    @Override
    public Mat processOutput(TranslatorContext ctx, NDList list) throws Exception {
        var array = list.get(0);
        return DjlTools.ndArrayToMat(array, outputLayoutNd);
    }

}
