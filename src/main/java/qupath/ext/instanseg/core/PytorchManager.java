package qupath.ext.instanseg.core;

import ai.djl.engine.Engine;
import ai.djl.engine.EngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Helper class to manage access to PyTorch via Deep Java Library.
 */
public class PytorchManager {

    private static final Logger logger = LoggerFactory.getLogger(PytorchManager.class);

    /**
     * Get the PyTorch engine, downloading if necessary.
     * @return the engine if available, or null if this failed
     */
    public static Engine getEngineOnline() {
        try {
            return callOnline(() -> Engine.getEngine("PyTorch"));
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get the available devices for PyTorch, including MPS if Apple Silicon.
     * @return Only "cpu" if no local engine is found.
     */
    public static Collection<String> getAvailableDevices() {
        try {
            Set<String> availableDevices = new LinkedHashSet<>();

            var engine = getEngineOffline();
            if (engine == null) {
                return List.of("cpu");
            }
            // This is expected to return GPUs if available, or CPU otherwise
            for (var device : engine.getDevices()) {
                String name = device.getDeviceType();
                availableDevices.add(name);
            }
            // If we could use MPS, but don't have it already, add it
            if (GeneralTools.isMac()) {
                availableDevices.add("mps");
            }
            // CPU should always be available
            availableDevices.add("cpu");

            return availableDevices;
        } catch (EngineException e) {
            logger.error("Unable to fetch engine", e);
            return List.of("cpu");
        }
    }

    /**
     * Query if the PyTorch engine is already available, without a need to download.
     * @return
     */
    public static boolean hasPyTorchEngine() {
        return getEngineOffline() != null;
    }

    /**
     * Get the PyTorch engine, without automatically downloading it if it isn't available.
     * @return the engine if available, or null otherwise
     */
    static Engine getEngineOffline() {
        try {
            return callOffline(() -> Engine.getEngine("PyTorch"));
        } catch (Exception e) {
            logger.debug("Failed to fetch offline engine", e);
            return null;
        }
    }

    /**
     * Call a function with the "offline" property set to true (to block automatic downloads).
     * @param callable
     * @return
     * @param <T>
     * @throws Exception
     */
    private static <T> T callOffline(Callable<T> callable) throws Exception {
        return callWithTempProperty("ai.djl.offline", "true", callable);
    }

    /**
     * Call a function with the "offline" property set to false (to allow automatic downloads).
     * @param callable
     * @return
     * @param <T>
     * @throws Exception
     */
    private static <T> T callOnline(Callable<T> callable) throws Exception {
        return callWithTempProperty("ai.djl.offline", "false", callable);
    }


    private static <T> T callWithTempProperty(String property, String value, Callable<T> callable) throws Exception {
        String oldValue = System.getProperty(property);
        System.setProperty(property, value);
        try {
            return callable.call();
        } finally {
            if (oldValue == null)
                System.clearProperty(property);
            else
                System.setProperty(property, oldValue);
        }
    }


}
