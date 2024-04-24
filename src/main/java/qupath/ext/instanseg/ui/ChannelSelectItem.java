package qupath.ext.instanseg.ui;

import qupath.lib.images.servers.ColorTransforms;

/**
 * Super simple class to deal with channel selection dropdown items that have different display and selection names.
 * e.g., the first channel in non-RGB images is shown as "Channel 1 (C1)" but the actual name is "Channel 1".
 */
class ChannelSelectItem {
    private final String name;
    private final ColorTransforms.ColorTransform transform;
    ChannelSelectItem(String name) {
        this.name = name;
        this.transform = ColorTransforms.createChannelExtractor(name);
    }

    ChannelSelectItem(String name, ColorTransforms.ColorTransform transform) {
        this.name = name;
        this.transform = transform;
    }

    @Override
    public String toString() {
        return this.name;
    }

    public String getName() {
        return name;
    }

    public ColorTransforms.ColorTransform getTransform() {
        return transform;
    }
}
