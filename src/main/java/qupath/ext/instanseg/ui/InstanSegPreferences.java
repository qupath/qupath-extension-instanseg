package qupath.ext.instanseg.ui;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.beans.property.StringProperty;
import qupath.lib.gui.prefs.PathPrefs;

public class InstanSegPreferences {

    private InstanSegPreferences() {
        throw new AssertionError("Cannot instantiate this class");
    }


    private static final StringProperty modelDirectoryProperty = PathPrefs.createPersistentPreference(
            "instanseg.model.dir",
            null);

    private static final StringProperty preferredDeviceProperty = PathPrefs.createPersistentPreference(
            "instanseg.pref.device",
            "cpu");

    private static final Property<Integer> numThreadsProperty = PathPrefs.createPersistentPreference(
            "instanseg.num.threads",
            Math.min(4, Runtime.getRuntime().availableProcessors())).asObject();

    private static final IntegerProperty tileSizeProperty = PathPrefs.createPersistentPreference(
            "intanseg.tile.size",
            512);

    public static StringProperty modelDirectoryProperty() {
        return modelDirectoryProperty;
    }

    public static StringProperty preferredDeviceProperty() {
        return preferredDeviceProperty;
    }

    public static Property<Integer> numThreadsProperty() {
        return numThreadsProperty;
    }

    public static IntegerProperty tileSizeProperty() {
        return tileSizeProperty;
    }
}
