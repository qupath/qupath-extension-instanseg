package qupath.ext.instanseg.core;

import org.bytedeco.opencv.opencv_core.Mat;
import qupath.lib.experimental.pixels.OpenCVProcessor;
import qupath.lib.experimental.pixels.OutputHandler;
import qupath.lib.experimental.pixels.Parameters;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.scripting.QP;
import qupath.opencv.tools.OpenCVTools;

import java.util.ArrayList;
import java.util.List;


class OutputToObjectConvert implements OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> {

    private OutputHandler.OutputToObjectConverter<Mat, Mat, Mat> converter = OpenCVProcessor.createDetectionConverter();

    @Override
    public List<PathObject> convertToObjects(Parameters params, Mat output) {
        List<PathObject> detections = new ArrayList<>();
        int channelCount = 0;
        for (var mat : OpenCVTools.splitChannels(output)) {
            List<PathObject> pathObjects = converter.convertToObjects(params, mat);
            if (output.channels() > 1) {
                PathClass pathClass = QP.getPathClass("Cell5", QP.makeRGB(90, 220, 90));
                if (channelCount == 0)
                    pathClass = QP.getPathClass("Nucleus3", QP.makeRGB(200, 70, 70));
                for (var p: pathObjects) {
                    p.setPathClass(pathClass);
                }
            }
            detections.addAll(pathObjects);
            channelCount++;
        }
        return detections;
    }
}
