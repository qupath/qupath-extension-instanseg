package qupath.ext.instanseg.ui;

import java.net.URL;

public class RemoteModel {
    private final String name;
    private final URL url;
    private final String version;
    private final String license;

    RemoteModel(String name, URL url, String version, String license) {
        this.name = name;
        this.url = url;
        this.version = version;
        this.license = license;
    }

    public String getName() {
        return name;
    }

    public URL getUrl() {
        return url;
    }

    public String getVersion() {
        return version;
    }

    public String getLicense() {
        return license;
    }
}
