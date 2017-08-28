/*
 * Copyright 2015 - 2017 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar;

import com.bettercloud.vault.SslConfig;
import com.bettercloud.vault.Vault;
import com.bettercloud.vault.VaultConfig;
import com.bettercloud.vault.VaultException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import javax.json.Json;
import javax.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Config {

    private final Logger logger = LoggerFactory.getLogger(Config.class);
    private final Properties properties = new Properties();
    private Vault vault;

    private boolean useEnvironmentVariables;

    void load(String file) throws IOException, VaultException {
        Properties mainProperties = new Properties();
        try (InputStream inputStream = new FileInputStream(file)) {
            logger.info("Loading properties file from {}", file);
            mainProperties.loadFromXML(inputStream);
        }

        String defaultConfigFile = mainProperties.getProperty("config.default");
        if (defaultConfigFile != null) {
            try (InputStream inputStream = new FileInputStream(defaultConfigFile)) {
                logger.info("Loading defaults from {}", defaultConfigFile);
                properties.loadFromXML(inputStream);
            }
        }

        properties.putAll(mainProperties); // override defaults

        useEnvironmentVariables = Boolean.parseBoolean(System.getenv("CONFIG_USE_ENVIRONMENT_VARIABLES"))
                || Boolean.parseBoolean(properties.getProperty("config.useEnvironmentVariables"));
        logger.info("useEnvironmentVariables set to {}", useEnvironmentVariables);
        setupVault();
    }

    private void setupVault() throws VaultException, IOException {
        String kubeValueCredsPath = System.getenv("CONFIG_KUBE_VAULT_CREDS_PATH");
        if (kubeValueCredsPath == null) {
            return;
        }
        logger.info("Configuring vault using kubernetes-vault");
        Path vaultTokenFile = Paths.get(kubeValueCredsPath, "vault-token");
        Path vaultCACert = Paths.get(kubeValueCredsPath, "ca.crt");
        try (Reader reader = new FileReader(vaultTokenFile.toString())) {
            JsonObject token = Json.createReader(reader).readObject();
            File caCert = new File(vaultCACert.toString());
            SslConfig sslConfig = new SslConfig();
            String vaultAddr = token.getString("vaultAddr");
            if (caCert.exists()) {
                logger.info("Found Vault CA cert at {}", vaultCACert);
                sslConfig.pemFile(caCert);
            }
            sslConfig.build();
            final VaultConfig vaultConfig = new VaultConfig()
                                          .address(vaultAddr)
                                          .token(token.getString("clientToken"))
                                          .sslConfig(sslConfig)
                                          .build();
            vault = new Vault(vaultConfig);
            logger.info("Successfully configured Vault at {}", vaultAddr);
        } catch (VaultException | IOException e) {
            throw e;
        }
    }

    public boolean hasKey(String key) {
        boolean present;
        if (vault != null) {
            logger.info("Atttempting to fetch key {} from vault", key);
            try {
                String value = vault.logical().read("secret/traccar").getData().get(key);
                if (value != null) {
                    logger.info("Found value for key {} in vault", key);
                    present = true;
                }
            } catch (VaultException e) {
                logger.info("Caught exception while trying to read key {} from Vault {}", key, e.getMessage());
                present = false;
            }
        }
        present = useEnvironmentVariables && System.getenv().containsKey(getEnvironmentVariableName(key))
                || properties.containsKey(key);
        return present;
    }

    public String getString(String key) {
        String value;
        if (vault != null) {
            try {
                value = vault.logical().read("secret/traccar").getData().get(key);
            } catch (VaultException e) {
                logger.info("Caught exception while trying to read key {} from Vault {}", key, e.getMessage());
                value = null;
            }
            if (value != null && !value.isEmpty()) {
                logger.info("Found value for key {} in vault", key);
                return value;
            }
        }
        if (useEnvironmentVariables) {
            value = System.getenv(getEnvironmentVariableName(key));
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return properties.getProperty(key);
    }

    public String getString(String key, String defaultValue) {
        return hasKey(key) ? getString(key) : defaultValue;
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    public int getInteger(String key) {
        return getInteger(key, 0);
    }

    public int getInteger(String key, int defaultValue) {
        return hasKey(key) ? Integer.parseInt(getString(key)) : defaultValue;
    }

    public long getLong(String key) {
        return getLong(key, 0);
    }

    public long getLong(String key, long defaultValue) {
        return hasKey(key) ? Long.parseLong(getString(key)) : defaultValue;
    }

    public double getDouble(String key) {
        return getDouble(key, 0.0);
    }

    public double getDouble(String key, double defaultValue) {
        return hasKey(key) ? Double.parseDouble(getString(key)) : defaultValue;
    }

    public static String getEnvironmentVariableName(String key) {
        return key.replaceAll("\\.", "_").replaceAll("(\\p{Lu})", "_$1").toUpperCase();
    }

    public void setString(String key, String value) {
        properties.put(key, value);
    }

}
