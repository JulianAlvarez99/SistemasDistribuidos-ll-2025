import java.io.*;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * 🔧 SYSTEM CONFIGURATION
 * Configuración centralizada del sistema distribuido
 */
public class SystemConfig {
    private static final Logger LOGGER = Logger.getLogger(SystemConfig.class.getName());
    private static SystemConfig instance;
    private final Properties properties;

    // Configuraciones por defecto
    public static final String DEFAULT_STORAGE_BASE_PATH = "C:/Users/julia/OneDrive/Desktop/RepoMaster/distributed_storage";
    public static final int DEFAULT_LOCK_TIMEOUT_MS = 30000;
    public static final int DEFAULT_SYNC_TIMEOUT_MS = 15000;
    public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 10000;
    public static final int DEFAULT_HEALTH_CHECK_INTERVAL_SEC = 30;
    public static final int DEFAULT_CLEANUP_INTERVAL_SEC = 300;
    public static final int DEFAULT_MAX_RETRIES = 3;

    private SystemConfig() {
        this.properties = new Properties();
        loadDefaultConfiguration();
        loadConfigurationFile();
    }

    public static synchronized SystemConfig getInstance() {
        if (instance == null) {
            instance = new SystemConfig();
        }
        return instance;
    }

    private void loadDefaultConfiguration() {
        // Storage paths
        properties.setProperty("storage.base.path", DEFAULT_STORAGE_BASE_PATH);
        properties.setProperty("storage.replica.prefix", "replica_");
        properties.setProperty("storage.backup.suffix", "_backup");

        // Timeouts (milliseconds) - Más largos para debugging
        properties.setProperty("timeout.lock.ms", String.valueOf(60000)); // 1 minuto
        properties.setProperty("timeout.sync.ms", String.valueOf(30000)); // 30 segundos
        properties.setProperty("timeout.connection.ms", String.valueOf(15000)); // 15 segundos
        properties.setProperty("timeout.read.ms", String.valueOf(15000)); // 15 segundos

        // Intervals (seconds)
        properties.setProperty("interval.health.check.sec", String.valueOf(DEFAULT_HEALTH_CHECK_INTERVAL_SEC));
        properties.setProperty("interval.cleanup.sec", String.valueOf(DEFAULT_CLEANUP_INTERVAL_SEC));

        // Retry and reliability - Más permisivo para desarrollo
        properties.setProperty("retry.max.attempts", String.valueOf(DEFAULT_MAX_RETRIES));
        properties.setProperty("consensus.require.unanimity", "false"); // Cambiar a mayoría por ahora
        properties.setProperty("replication.verify.writes", "true");

        // Network
        properties.setProperty("network.default.ports", "8080,8081,8082");
        properties.setProperty("network.default.host", "localhost");

        // Logging - Más verbose para debugging
        properties.setProperty("logging.level", "INFO");
        properties.setProperty("logging.enable.debug", "true");
    }

    private void loadConfigurationFile() {
        String configFile = System.getProperty("config.file", "system.properties");
        File file = new File(configFile);

        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                properties.load(fis);
                LOGGER.info("Configuration loaded from: " + configFile);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to load config file: " + configFile, e);
            }
        } else {
            LOGGER.info("No config file found, using defaults");
        }
    }

    // Storage Configuration
    public String getStorageBasePath() {
        return properties.getProperty("storage.base.path", DEFAULT_STORAGE_BASE_PATH);
    }

    public String getReplicaStoragePath(int port) {
        String basePath = getStorageBasePath();
        String prefix = properties.getProperty("storage.replica.prefix", "replica_");
        return basePath + "/" + prefix + port + "_storage";
    }

    // Timeout Configuration
    public int getLockTimeoutMs() {
        return Integer.parseInt(properties.getProperty("timeout.lock.ms", String.valueOf(DEFAULT_LOCK_TIMEOUT_MS)));
    }

    public int getSyncTimeoutMs() {
        return Integer.parseInt(properties.getProperty("timeout.sync.ms", String.valueOf(DEFAULT_SYNC_TIMEOUT_MS)));
    }

    public int getConnectionTimeoutMs() {
        return Integer.parseInt(properties.getProperty("timeout.connection.ms", String.valueOf(DEFAULT_CONNECTION_TIMEOUT_MS)));
    }

    public int getReadTimeoutMs() {
        return Integer.parseInt(properties.getProperty("timeout.read.ms", String.valueOf(DEFAULT_CONNECTION_TIMEOUT_MS)));
    }

    // Interval Configuration
    public int getHealthCheckIntervalSec() {
        return Integer.parseInt(properties.getProperty("interval.health.check.sec", String.valueOf(DEFAULT_HEALTH_CHECK_INTERVAL_SEC)));
    }

    public int getCleanupIntervalSec() {
        return Integer.parseInt(properties.getProperty("interval.cleanup.sec", String.valueOf(DEFAULT_CLEANUP_INTERVAL_SEC)));
    }

    // Retry and Reliability Configuration
    public int getMaxRetries() {
        return Integer.parseInt(properties.getProperty("retry.max.attempts", String.valueOf(DEFAULT_MAX_RETRIES)));
    }

    public boolean requireUnanimousConsensus() {
        return Boolean.parseBoolean(properties.getProperty("consensus.require.unanimity", "true"));
    }

    public boolean verifyWrites() {
        return Boolean.parseBoolean(properties.getProperty("replication.verify.writes", "true"));
    }

    // Network Configuration
    public String getDefaultHost() {
        return properties.getProperty("network.default.host", "localhost");
    }

    public int[] getDefaultPorts() {
        String portsStr = properties.getProperty("network.default.ports", "8080,8081,8082");
        String[] portStrings = portsStr.split(",");
        int[] ports = new int[portStrings.length];
        for (int i = 0; i < portStrings.length; i++) {
            ports[i] = Integer.parseInt(portStrings[i].trim());
        }
        return ports;
    }

    // Logging Configuration
    public String getLoggingLevel() {
        return properties.getProperty("logging.level", "INFO");
    }

    public boolean isDebugEnabled() {
        return Boolean.parseBoolean(properties.getProperty("logging.enable.debug", "false"));
    }

    // Utility methods
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }

    public void saveConfiguration(String filename) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filename)) {
            properties.store(fos, "Distributed File System Configuration");
            LOGGER.info("Configuration saved to: " + filename);
        }
    }

    @Override
    public String toString() {
        return String.format("SystemConfig{storagePath='%s', lockTimeout=%dms, syncTimeout=%dms, maxRetries=%d}",
                getStorageBasePath(), getLockTimeoutMs(), getSyncTimeoutMs(), getMaxRetries());
    }
}