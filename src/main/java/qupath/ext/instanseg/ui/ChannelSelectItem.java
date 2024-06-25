package qupath.ext.instanseg.ui;

import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.images.servers.ColorTransforms;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Super simple class to deal with channel selection dropdown items that have different display and selection names.
 * e.g., the first channel in non-RGB images is shown as "Channel 1 (C1)" but the actual name is "Channel 1".
 */
class ChannelSelectItem {
    private final String name;
    private final ColorTransforms.ColorTransform transform;
    private final String constructor;

    // todo: public method to get a constructor for the colortransform
    ChannelSelectItem(String name) {
        this.name = name;
        this.transform = ColorTransforms.createChannelExtractor(name);
        this.constructor = String.format("ColorTransforms.createChannelExtractor(\"%s\")", name);
    }

    ChannelSelectItem(String name, int i) {
        this.name = name;
        this.transform = ColorTransforms.createChannelExtractor(i);
        this.constructor = String.format("ColorTransforms.createChannelExtractor(%d)", i);
    }

    ChannelSelectItem(ColorDeconvolutionStains stains, int i) {
        this.name = stains.getStain(i).getName();
        this.transform = ColorTransforms.createColorDeconvolvedChannel(stains, i);
        this.constructor = String.format("ColorTransforms.createColorDeconvolvedChannel(stains, %d)", i);
    }

    @Override
    public String toString() {
        return this.name;
    }

    String getName() {
        return name;
    }

    ColorTransforms.ColorTransform getTransform() {
        return transform;
    }

    private String getConstructor() {
        return this.constructor;
    }

    static String toConstructorString(Collection<ChannelSelectItem> items) {
        return "List.of(" + items.stream().map(ChannelSelectItem::getConstructor).collect(Collectors.joining(", ")) + ")";
    }
}
