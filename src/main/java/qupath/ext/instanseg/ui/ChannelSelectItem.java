package qupath.ext.instanseg.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Super simple class to deal with channel selection dropdown items that have different display and selection names.
 * e.g., the first channel in non-RGB images is shown as "Channel 1 (C1)" but the actual name is "Channel 1".
 */
class ChannelSelectItem {

    private static final Logger logger = LoggerFactory.getLogger(ChannelSelectItem.class);

    private final String name;
    private final ColorTransforms.ColorTransform transform;
    private final String constructor;

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

    String getConstructor() {
        return this.constructor;
    }

    /**
     * Get a list of available channels for the given image data.
     * @param imageData
     * @return
     */
    static List<ChannelSelectItem> getAvailableChannels(ImageData<?> imageData) {
        List<ChannelSelectItem> list = new ArrayList<>();
        Set<String> names = new HashSet<>();
        var server = imageData.getServer();
        int i = 1;
        boolean hasDuplicates = false;
        ChannelSelectItem item;
        for (var channel : server.getMetadata().getChannels()) {
            var name = channel.getName();
            if (names.contains(name)) {
                logger.warn("Found duplicate channel name! Channel " + i + " (name '" + name + "').");
                logger.warn("Using channel indices instead of names because of duplicated channel names.");
                hasDuplicates = true;
            }
            names.add(name);
            if (hasDuplicates) {
                item = new ChannelSelectItem(name, i - 1);
            } else {
                item = new ChannelSelectItem(name);
            }
            list.add(item);
            i++;
        }
        var stains = imageData.getColorDeconvolutionStains();
        if (stains != null) {
            for (i = 1; i < 4; i++) {
                list.add(new ChannelSelectItem(stains, i));
            }
        }
        return list;
    }

    static String toConstructorString(Collection<ChannelSelectItem> items) {
        if (items == null || items.isEmpty())
            return "allInputChannels()";
        else
            return "inputChannels([" + items.stream().map(ChannelSelectItem::getConstructor).collect(Collectors.joining(", ")) + "])";
    }
}
