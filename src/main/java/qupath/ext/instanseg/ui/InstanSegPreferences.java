package qupath.ext.instanseg.ui;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.beans.property.StringProperty;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.prefs.PathPrefs;

class InstanSegPreferences {

    private InstanSegPreferences() {
        throw new AssertionError("Cannot instantiate this class");
    }

    private static final StringProperty modelDirectoryProperty = PathPrefs.createPersistentPreference(
            "instanseg.model.dir",
            null);

    private static final StringProperty preferredDeviceProperty = PathPrefs.createPersistentPreference(
            "instanseg.pref.device",
            getDefaultDevice());

    private static final Property<Integer> numThreadsProperty = PathPrefs.createPersistentPreference(
            "instanseg.num.threads",
            Math.min(4, Runtime.getRuntime().availableProcessors())).asObject();

    private static final IntegerProperty tileSizeProperty = PathPrefs.createPersistentPreference(
            "intanseg.tile.size",
            512);

    /**
     * MPS should work reliably (and much faster) on Apple Silicon, so set as default.
     * Everywhere else, use CPU as we can't count on a GPU/CUDA being available.
     * @return
     */
    private static String getDefaultDevice() {
        if (GeneralTools.isMac() && "aarch64".equals(System.getProperty("os.arch"))) {
            return "mps";
        } else {
            return "cpu";
        }
    }

    static StringProperty modelDirectoryProperty() {
        return modelDirectoryProperty;
    }

    static StringProperty preferredDeviceProperty() {
        return preferredDeviceProperty;
    }

    static Property<Integer> numThreadsProperty() {
        return numThreadsProperty;
    }

    static IntegerProperty tileSizeProperty() {
        return tileSizeProperty;
    }
}
