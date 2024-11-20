package qupath.ext.instanseg.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class GitHubUtils {

    private static final Logger logger = LoggerFactory.getLogger(GitHubUtils.class);

    static class GitHubRelease {

        private String tag_name;
        private String name;
        private Date published_at;
        private GitHubAsset[] assets;
        private String body;

        String getName() {
            return name;
        }
        String getBody() {
            return body;
        }
        Date getDate() {
            return published_at;
        }
        String getTag() {
            return tag_name;
        }

        @Override
        public String toString() {
            return name + " with assets:" + Arrays.toString(assets);
        }
    }

    static class GitHubAsset {

        private String name;
        private String content_type;
        private URL browser_download_url;

        String getType() {
            return content_type;
        }

        URL getUrl() {
            return browser_download_url;
        }

        String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }

    }

    /**
     * Get the list of models from the latest GitHub release, downloading if
     * necessary.
     * @return A list of GitHub releases, possibly empty.
     */
    static List<GitHubRelease> getReleases(Path modelDir) {
        Path cachedReleases = modelDir == null ? null : modelDir.resolve("releases.json");

        String uString = "https://api.github.com/repos/alanocallaghan/InstanSeg/releases";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uString))
                .GET()
                .build();
        HttpResponse<String> response;
        String json;
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        // check GitHub api for releases
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            // if response is okay, then cache it
            if (response.statusCode() == 200) {
                json = response.body();
                if (cachedReleases != null && Files.exists(cachedReleases.getParent())) {
                    JsonElement jsonElement = JsonParser.parseString(json);
                    Files.writeString(cachedReleases, gson.toJson(jsonElement));
                } else {
                    logger.debug("Unable to cache release information - no model directory specified");
                }
            } else {
                // otherwise problems
                throw new IOException("Unable to fetch GitHub release information, status " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            // if not, try to fall back on a cached version
            if (cachedReleases != null && Files.exists(cachedReleases)) {
                try {
                    json = Files.readString(cachedReleases);
                } catch (IOException ex) {
                    logger.warn("Unable to read cached release information");
                    return List.of();
                }
            } else {
                logger.info("Unable to fetch release information from GitHub and no cached version available.");
                return List.of();
            }
        }

        GitHubRelease[] releases = gson.fromJson(json, GitHubRelease[].class);
        if (!(releases.length > 0)) {
            logger.info("No releases found in JSON string");
            return List.of();
        }
        return List.of(releases);
    }


    static List<GitHubAsset> getAssets(GitHubRelease release) {
        var assets = Arrays.stream(release.assets)
                .filter(a -> a.getType().equals("application/zip"))
                .toList();
        if (assets.isEmpty()) {
            logger.info("No valid assets identified for {}", release.name);
        } else if (assets.size() > 1) {
            logger.info("More than one matching model: {}", release.name);
        }
        return assets;
    }


}
