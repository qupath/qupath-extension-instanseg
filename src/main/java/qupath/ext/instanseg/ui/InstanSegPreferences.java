package qupath.ext.instanseg.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.Property;
import javafx.beans.property.StringProperty;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.prefs.PathPrefs;

class InstanSegPreferences {

    private InstanSegPreferences() {
        throw new AssertionError("Cannot instantiate this class");
    }

    /**
     * Enum for whether we can look for models online.
     */
    enum OnlinePermission {
        YES, NO, PROMPT
    }

    private static final ObjectProperty<OnlinePermission> permitOnlineProperty = PathPrefs.createPersistentPreference(
            "instanseg.download.models",
            OnlinePermission.PROMPT, OnlinePermission.class);

    private static final StringProperty modelDirectoryProperty = PathPrefs.createPersistentPreference(
            "instanseg.model.dir",
            null);

    private static final StringProperty preferredDeviceProperty = PathPrefs.createPersistentPreference(
            "instanseg.pref.device",
            getDefaultDevice());

    private static final IntegerProperty numThreadsProperty = PathPrefs.createPersistentPreference(
            "instanseg.num.threads",
            GeneralTools.clipValue(Runtime.getRuntime().availableProcessors() / 2, 2, 4));

    private static final IntegerProperty tileSizeProperty = PathPrefs.createPersistentPreference(
            "intanseg.tile.size",
            512);

    private static final IntegerProperty tilePaddingProperty = PathPrefs.createPersistentPreference(
            "intanseg.tile.padding",
            32);

    private static final BooleanProperty makeMeasurementsProperty = PathPrefs.createPersistentPreference(
            "intanseg.measurements",
            true);

    private static final BooleanProperty randomColorsProperty = PathPrefs.createPersistentPreference(
            "intanseg.random.colors",
            false);

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

    static ObjectProperty<OnlinePermission> permitOnlineProperty() {
        return permitOnlineProperty;
    }

    static StringProperty modelDirectoryProperty() {
        return modelDirectoryProperty;
    }

    static StringProperty preferredDeviceProperty() {
        return preferredDeviceProperty;
    }

    static IntegerProperty numThreadsProperty() {
        return numThreadsProperty;
    }

    static IntegerProperty tileSizeProperty() {
        return tileSizeProperty;
    }

    static IntegerProperty tilePaddingProperty() {
        return tilePaddingProperty;
    }

    static BooleanProperty makeMeasurementsProperty() {
        return makeMeasurementsProperty;
    }

    static BooleanProperty randomColorsProperty() {
        return randomColorsProperty;
    }

}
