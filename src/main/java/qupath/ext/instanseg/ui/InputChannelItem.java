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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Super simple class to deal with channel selection dropdown items that have different display and selection names.
 * e.g., the first channel in non-RGB images is shown as "Channel 1 (C1)" but the actual name is "Channel 1".
 */
class InputChannelItem {

    private static final Logger logger = LoggerFactory.getLogger(InputChannelItem.class);

    private final String name;
    private final ColorTransforms.ColorTransform transform;
    private final String constructor;

    InputChannelItem(String name) {
        this.name = name;
        this.transform = ColorTransforms.createChannelExtractor(name);
        this.constructor = String.format("ColorTransforms.createChannelExtractor(\"%s\")", name);
    }

    InputChannelItem(String name, int i) {
        this.name = name;
        this.transform = ColorTransforms.createChannelExtractor(i);
        this.constructor = String.format("ColorTransforms.createChannelExtractor(%d)", i);
    }

    InputChannelItem(ColorDeconvolutionStains stains, int i) {
        this.name = stains.getStain(i).getName();
        this.transform = ColorTransforms.createColorDeconvolvedChannel(stains, i);
        this.constructor = String.format("ColorTransforms.createColorDeconvolvedChannel(QP.getCurrentImageData().getColorDeconvolutionStains(), %d)", i);
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
     * @param imageData The image data.
     * @return A list of channels, including color deconvolution channels if present.
     */
    static List<InputChannelItem> getAvailableChannels(ImageData<?> imageData) {
        List<InputChannelItem> list = new ArrayList<>();
        Set<String> names = new HashSet<>();
        var server = imageData.getServer();
        int i = 1;
        boolean hasDuplicates = false;
        InputChannelItem item;
        for (var channel : server.getMetadata().getChannels()) {
            var name = channel.getName();
            if (names.contains(name)) {
                logger.warn("Found duplicate channel name! Channel " + i + " (name '" + name + "').");
                logger.warn("Using channel indices instead of names because of duplicated channel names.");
                hasDuplicates = true;
            }
            names.add(name);
            if (hasDuplicates) {
                item = new InputChannelItem(name, i - 1);
            } else {
                item = new InputChannelItem(name);
            }
            list.add(item);
            i++;
        }
        var stains = imageData.getColorDeconvolutionStains();
        if (stains != null) {
            for (i = 1; i < 4; i++) {
                list.add(new InputChannelItem(stains, i));
            }
        }
        return list;
    }

    static String toConstructorString(Collection<InputChannelItem> items) {
        if (items == null || items.isEmpty())
            return "allInputChannels()";
        else
            return "inputChannels([" + items.stream().map(InputChannelItem::getConstructor).collect(Collectors.joining(", ")) + "])";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InputChannelItem that)) return false;
        // Note that we don't compare the transform - the constructor is sufficient, because we don't
        // encode color deconvolution stains here
        return Objects.equals(name, that.name) && Objects.equals(constructor, that.constructor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, constructor);
    }
}
