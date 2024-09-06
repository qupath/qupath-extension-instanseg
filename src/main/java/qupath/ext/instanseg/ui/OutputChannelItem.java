package qupath.ext.instanseg.ui;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Helper class for selecting which channels of the InstanSeg output to use.
 */
public class OutputChannelItem {

    private final int index;
    private final String name;

    private static final List<OutputChannelItem> SINGLE_CHANNEL = List.of(
            new OutputChannelItem(0, "Only channel")
    );

    // We have no way to query channel names currently - b
    // but the first InstanSeg models with two channels are always in this order
    private static final List<OutputChannelItem> TWO_CHANNEL = List.of(
            new OutputChannelItem(0, "Channel 1 (Nuclei)"),
            new OutputChannelItem(0, "Channel 2 (Cells)")
    );

    private OutputChannelItem(int index, String name) {
        this.index = index;
        this.name = name;
    }

    static List<OutputChannelItem> getOutputsForChannelCount(int nChannels) {
        return switch (nChannels) {
            case 0 -> List.of();
            case 1 -> SINGLE_CHANNEL;
            case 2 -> TWO_CHANNEL;
            default ->
                    IntStream.range(0, nChannels).mapToObj(i -> new OutputChannelItem(i, "Channel " + (i + 1))).toList();
        };
    }

    /**
     * Get the index of the output channel.
     * @return
     */
    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return name;
    }

}
