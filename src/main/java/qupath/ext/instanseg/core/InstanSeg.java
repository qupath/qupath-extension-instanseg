package qupath.ext.instanseg.core;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.scripting.QP;

import java.util.Collection;

public class InstanSeg {
    // todo: chainable setters, and throw warnings for any missing params...?
    int tileDims = 512;
    double downsample = 1;
    int padding = 40;
    int boundary = 20;
    boolean twoChannel;
    ImageData<?> imageData;
    Collection<ColorTransforms.ColorTransform> channels;
    TaskRunner taskRunner;
    InstanSegModel model;

    // todo: API like eg... new InstanSeg().padding(40).channels(List.of(1, 2, 3)).runInference() ?

    public void runInference() {

    }

}
