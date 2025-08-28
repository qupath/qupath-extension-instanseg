package qupath.ext.instanseg.core;

import ai.djl.Device;
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
     * Returns just "cpu" if no local engine is found, as that device is always available.
     * For discrete GPUs, it appends the device number to the device in a format that can be parsed by {@link Device#fromName(String)}.
     * For example, the first GPU is "gpu0", the second is "gpu1", etc.
     * @return the available devices in a format that can be parsed by {@link Device#fromName(String)}.
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
                // append the device ID for any devices that may be one of many (mainly GPUs)
                // this means they can be parsed by Device.fromName(String name)
                if (device.getDeviceId() != -1) {
                    name += device.getDeviceId(); // gpu0, gpu1 etc
                }
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
     * @return whether PyTorch is available.
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
     * @param callable The function to be called.
     * @return The return value of the callable.
     * @param <T> The return type of the callable.
     * @throws Exception If the callable does.
     */
    private static <T> T callOffline(Callable<T> callable) throws Exception {
        return callWithTempProperty("ai.djl.offline", "true", callable);
    }

    /**
     * Call a function with the "offline" property set to false (to allow automatic downloads).
     * @param callable The function to be called.
     * @return The return value of the callable.
     * @param <T> The return type of the callable.
     * @throws Exception If the callable does.
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
