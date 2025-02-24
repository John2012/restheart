/*-
 * ========================LICENSE_START=================================
 * restheart-core
 * %%
 * Copyright (C) 2014 - 2020 SoftInstigate
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.restheart;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.MustacheNotFoundException;
import com.google.common.collect.Maps;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.jxpath.JXPathContext;
import org.restheart.utils.URLUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.restheart.ConfigurationKeys.*;
import static org.restheart.utils.ConfigurationUtils.*;

/**
 * Class that holds the configuration.
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class Configuration {

    /**
     * the version is read from the JAR's MANIFEST.MF file, which is automatically
     * generated by the Maven build process
     */
    public static final String VERSION = Configuration.class.getPackage().getImplementationVersion() == null
            ? "unknown, not packaged"
            : Configuration.class.getPackage().getImplementationVersion();

    private static final Logger LOGGER = LoggerFactory.getLogger(Configuration.class);

    public static final String DEFAULT_ROUTE = "0.0.0.0";

    /**
     * hold the path of the configuration file
     */
    private static Path PATH = null;

    private final boolean httpsListener;
    private final int httpsPort;
    private final String httpsHost;
    private final boolean httpListener;
    private final int httpPort;
    private final String httpHost;
    private final boolean ajpListener;
    private final int ajpPort;
    private final String ajpHost;
    private final String instanceName;
    private final String pluginsDirectory;
    private final String keystoreFile;
    private final String keystorePassword;
    private final String certPassword;
    private final List<Map<String, Object>> proxies;
    private final List<Map<String, Object>> staticResourcesMounts;
    private final Map<String, Map<String, Object>> pluginsArgs;
    private final Map<String, Map<String, Object>> authMechanisms;
    private final Map<String, Map<String, Object>> authenticators;
    private final Map<String, Map<String, Object>> authorizers;
    private final Map<String, Map<String, Object>> tokenManagers;
    private final String logFilePath;
    private final Level logLevel;
    private final boolean logToConsole;
    private final boolean logToFile;
    private final List<String> traceHeaders;
    private final int requestsLimit;
    private final int ioThreads;
    private final int workerThreads;
    private final int bufferSize;
    private final boolean directBuffers;
    private final boolean forceGzipEncoding;
    private final Map<String, Object> connectionOptions;
    private final Integer logExchangeDump;
    private final boolean ansiConsole;
    private final boolean allowUnescapedCharactersInUrl;

    private Map<String, Object> conf;

    /**
     * Creates a new instance of Configuration from the configuration file For any
     * missing property the default value is used.
     *
     * @param conf   the key-value configuration map
     * @param silent
     * @throws org.restheart.ConfigurationException
     */
    private Configuration(Map<String, Object> conf, final Path confFilePath, boolean silent) throws ConfigurationException {
        PATH = confFilePath;

        this.conf = conf;

        ansiConsole = asBoolean(conf, ANSI_CONSOLE_KEY, true, silent);

        httpsListener = asBoolean(conf, HTTPS_LISTENER_KEY, true, silent);
        httpsPort = asInteger(conf, HTTPS_PORT_KEY, DEFAULT_HTTPS_PORT, silent);
        httpsHost = asString(conf, HTTPS_HOST_KEY, DEFAULT_HTTPS_HOST, silent);

        httpListener = asBoolean(conf, HTTP_LISTENER_KEY, false, silent);
        httpPort = asInteger(conf, HTTP_PORT_KEY, DEFAULT_HTTP_PORT, silent);
        httpHost = asString(conf, HTTP_HOST_KEY, DEFAULT_HTTP_HOST, silent);

        ajpListener = asBoolean(conf, AJP_LISTENER_KEY, DEFAULT_AJP_LISTENER, silent);
        ajpPort = asInteger(conf, AJP_PORT_KEY, DEFAULT_AJP_PORT, silent);
        ajpHost = asString(conf, AJP_HOST_KEY, DEFAULT_AJP_HOST, silent);

        instanceName = asString(conf, INSTANCE_NAME_KEY, DEFAULT_INSTANCE_NAME, silent);
        keystoreFile = asString(conf, KEYSTORE_FILE_KEY, null, silent);
        keystorePassword = asString(conf, KEYSTORE_PASSWORD_KEY, null, silent);
        certPassword = asString(conf, CERT_PASSWORD_KEY, null, silent);

        proxies = asListOfMaps(conf, PROXY_KEY, new ArrayList<>(), silent);

        staticResourcesMounts = asListOfMaps(conf, STATIC_RESOURCES_MOUNTS_KEY, new ArrayList<>(), silent);

        pluginsDirectory = asString(conf, PLUGINS_DIRECTORY_PATH_KEY, null, silent);

        pluginsArgs = asMapOfMaps(conf, PLUGINS_ARGS_KEY, new LinkedHashMap<>(), silent);

        authMechanisms = asMapOfMaps(conf, AUTH_MECHANISMS_KEY, new LinkedHashMap<>(), silent);

        authenticators = asMapOfMaps(conf, AUTHENTICATORS_KEY, new LinkedHashMap<>(), silent);

        authorizers = asMapOfMaps(conf, AUTHORIZERS_KEY, new LinkedHashMap<>(), silent);

        tokenManagers = asMapOfMaps(conf, TOKEN_MANAGER_KEY, new LinkedHashMap<>(), silent);

        logFilePath = asString(conf, LOG_FILE_PATH_KEY, URLUtils.removeTrailingSlashes(System.getProperty("java.io.tmpdir")).concat(File.separator + "restheart.log"), silent);
        String _logLevel = asString(conf, LOG_LEVEL_KEY, "INFO", silent);
        logToConsole = asBoolean(conf, ENABLE_LOG_CONSOLE_KEY, true, silent);
        logToFile = asBoolean(conf, ENABLE_LOG_FILE_KEY, true, silent);

        Level level;
        try {
            level = Level.valueOf(_logLevel);
        } catch (Exception e) {
            if (!silent) {
                LOGGER.info("wrong value for parameter {}: {}, using its default value {}", "log-level", _logLevel, "INFO");
            }
            level = Level.INFO;
        }

        logLevel = level;

        traceHeaders = asListOfStrings(conf, REQUESTS_LOG_TRACE_HEADERS_KEY, Collections.emptyList(), silent);

        requestsLimit = asInteger(conf, REQUESTS_LIMIT_KEY, 100, silent);
        ioThreads = asInteger(conf, IO_THREADS_KEY, 2, silent);
        workerThreads = asInteger(conf, WORKER_THREADS_KEY, 32, silent);
        bufferSize = asInteger(conf, BUFFER_SIZE_KEY, 16384, silent);
        directBuffers = asBoolean(conf, DIRECT_BUFFERS_KEY, true, silent);
        forceGzipEncoding = asBoolean(conf, FORCE_GZIP_ENCODING_KEY, false, silent);
        logExchangeDump = asInteger(conf, LOG_REQUESTS_LEVEL_KEY, 0, silent);
        connectionOptions = asMap(conf, CONNECTION_OPTIONS_KEY, silent);
        allowUnescapedCharactersInUrl = asBoolean(conf, ALLOW_UNESCAPED_CHARACTERS_IN_URL, true, silent);
    }

    @Override
    public String toString() {
        var sw = new StringWriter();
        new Yaml().dump(conf, sw);

        return sw.toString();
    }

    public Map<String, Object> toMap() {
        return Collections.unmodifiableMap(this.conf);
    }

    /**
     * @return the proxies
     */
    public List<Map<String, Object>> getProxies() {
        return Collections.unmodifiableList(proxies);
    }

    /**
     * @return the staticResourcesMounts
     */
    public List<Map<String, Object>> getStaticResourcesMounts() {
        return Collections.unmodifiableList(staticResourcesMounts);
    }

    /**
     *
     * @return true if the Ansi console is enabled
     */
    public boolean isAnsiConsole() {
        return ansiConsole;
    }

    /**
     * @return the httpsListener
     */
    public boolean isHttpsListener() {
        return httpsListener;
    }

    /**
     * @return the httpsPort
     */
    public int getHttpsPort() {
        return httpsPort;
    }

    /**
     * @return the httpsHost
     */
    public String getHttpsHost() {
        return httpsHost;
    }

    /**
     * @return the httpListener
     */
    public boolean isHttpListener() {
        return httpListener;
    }

    /**
     * @return the httpPort
     */
    public int getHttpPort() {
        return httpPort;
    }

    /**
     * @return the httpHost
     */
    public String getHttpHost() {
        return httpHost;
    }

    /**
     * @return the ajpListener
     */
    public boolean isAjpListener() {
        return ajpListener;
    }

    /**
     * @return the ajpPort
     */
    public int getAjpPort() {
        return ajpPort;
    }

    /**
     * @return the ajpHost
     */
    public String getAjpHost() {
        return ajpHost;
    }

    /**
     * @return the pluginsDirectory
     */
    public String getPluginsDirectory() {
        return this.pluginsDirectory;
    }

    /**
     * @return the keystoreFile
     */
    public String getKeystoreFile() {
        return keystoreFile;
    }

    /**
     * @return the keystorePassword
     */
    public String getKeystorePassword() {
        return keystorePassword;
    }

    /**
     * @return the certPassword
     */
    public String getCertPassword() {
        return certPassword;
    }

    /**
     * @return the logFilePath
     */
    public String getLogFilePath() {
        return logFilePath;
    }

    /**
     * @return the logLevel
     */
    public Level getLogLevel() {
        var logbackConfigurationFile = System.getProperty("logback.configurationFile");
        if (logbackConfigurationFile != null && !logbackConfigurationFile.isEmpty()) {
            var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            var logger = loggerContext.getLogger("org.restheart.security");
            return logger.getLevel();
        }

        return logLevel;
    }

    /**
     * @return the logToConsole
     */
    public boolean isLogToConsole() {
        return logToConsole;
    }

    /**
     * @return the logToFile
     */
    public boolean isLogToFile() {
        return logToFile;
    }

    public List<String> getTraceHeaders() {
        return Collections.unmodifiableList(traceHeaders);
    }

    /**
     * @return the ioThreads
     */
    public int getIoThreads() {
        return ioThreads;
    }

    /**
     * @return the workerThreads
     */
    public int getWorkerThreads() {
        return workerThreads;
    }

    /**
     * @return the bufferSize
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * @return the directBuffers
     */
    public boolean isDirectBuffers() {
        return directBuffers;
    }

    /**
     * @return the forceGzipEncoding
     */
    public boolean isForceGzipEncoding() {
        return forceGzipEncoding;
    }

    /**
     * @return the pluginsArgs
     */
    public Map<String, Map<String, Object>> getPluginsArgs() {
        return Collections.unmodifiableMap(pluginsArgs);
    }

    /**
     * @return the authMechanisms
     */
    public Map<String, Map<String, Object>> getAuthMechanisms() {
        return Collections.unmodifiableMap(authMechanisms);
    }

    /**
     * @return the authenticators
     */
    public Map<String, Map<String, Object>> getAuthenticators() {
        return Collections.unmodifiableMap(authenticators);
    }

    /**
     * @return the authorizers
     */
    public Map<String, Map<String, Object>> getAuthorizers() {
        return Collections.unmodifiableMap(authorizers);
    }

    /**
     * @return the requestsLimit
     */
    public int getRequestsLimit() {
        return requestsLimit;
    }

    /**
     * @return the tokenManagers
     */
    public Map<String, Map<String, Object>> getTokenManagers() {
        return Collections.unmodifiableMap(tokenManagers);
    }

    /**
     *
     * @return the logExchangeDump Boolean
     */
    public Integer logExchangeDump() {
        return logExchangeDump;
    }

    /**
     * @return the connectionOptions
     */
    public Map<String, Object> getConnectionOptions() {
        return Collections.unmodifiableMap(connectionOptions);
    }

    /**
     * @return the instanceName
     */
    public String getInstanceName() {
        return instanceName;
    }

    public boolean isAllowUnescapedCharactersInUrl() {
        return allowUnescapedCharactersInUrl;
    }

    /**
     *
     * @return the path of the configuration file
     */
    public static Path getPath() {
        return PATH;
    }

    static boolean isParametric(final Path confFilePath) throws IOException {
        try (var sc = new Scanner(confFilePath, "UTF-8")) {
            return sc.findAll(Pattern.compile("\\{\\{.*\\}\\}")).limit(1).count() > 0;
        }
    }

    public class Builder {
        /**
         *
         * @return the default configuration
         */
        public static Configuration build(boolean silent) {
            return build(null, null, silent);
        }

        /**
         *
         * @param confFile
         * @return return the configuration from confFile and propFile
         */
        public static Configuration build(Path confFilePath, Path propFilePath, boolean silent) throws ConfigurationException {
            if (confFilePath == null) {
                var stream = Configuration.class.getResourceAsStream("/restheart-default-config.yml");
                try (var confReader = new InputStreamReader(stream)) {
                    return build(confReader, null, null, silent);
                } catch (IOException ieo) {
                    throw new ConfigurationException("Error reading default configuration file", ieo);
                }
            } else {
                try (var confReader = new BufferedReader(new FileReader(confFilePath.toFile()))) {
                    return build(confReader, confFilePath, propFilePath, silent);
                } catch (MustacheNotFoundException | FileNotFoundException ex) {
                    throw new ConfigurationException("Configuration file not found: " + confFilePath);
                } catch (IOException ieo) {
                    throw new ConfigurationException("Error reading configuration file " + confFilePath, ieo);
                }
            }
        }

        /**
         *
         * @param confFile
         * @return return the configuration from confFile and propFile
         */
        private static Configuration build(Reader confReader, Path confFilePath, Path propFilePath, boolean silent) throws ConfigurationException {
            var m = new DefaultMustacheFactory().compile(confReader, "configuration-file");

            var confFileParams = Arrays.asList(m.getCodes());

            if (confFileParams.isEmpty()) {
                // configuration file is not parametric
                Map<String, Object> confMap = new Yaml(new SafeConstructor()).load(confReader);

                return new Configuration(overrideConfiguration(confMap, silent), confFilePath, silent);
            } else {
                // configuration is parametric
                if (propFilePath == null) {
                    // check if parameters are defined via env vars
                    var allResolved = confFileParams.stream().filter(c -> c != null && c.getName() != null).map(c -> c.getName()).allMatch(n -> valueFromEnv(n, true) != null);

                    if (allResolved) {
                        final var p = new Properties();
                        confFileParams.stream()
                            .filter(c -> c != null && c.getName() != null)
                            .map(c -> c.getName())
                            .forEach(n -> p.put(n, valueFromEnv(n, silent)));

                        final var writer = new StringWriter();
                        m.execute(writer, p);

                        Map<String, Object> confMap = new Yaml(new SafeConstructor()).load(writer.toString());
                        return new Configuration(overrideConfiguration(confMap, silent), confFilePath, silent);
                    } else {
                        var unbound = confFileParams.stream()
                            .filter(c -> c != null && c.getName() != null)
                            .map(c -> c.getName())
                            .filter(n -> valueFromEnv(n, true) == null)
                            .collect(Collectors.toList());

                        throw new ConfigurationException("Configuration is parametric but no properties file or environment variables have been specified."
                                + " Unbound parameters: " + unbound.toString()
                                + ". You can use -e option to specify the properties file or set them via environment variables"
                                + ". For more information check https://restheart.org/docs/setup/#configuration-files");
                    }
                } else {
                    try (var propsReader = new InputStreamReader(new FileInputStream(propFilePath.toFile()), "UTF-8")) {
                        final var p = new Properties();
                        p.load(propsReader);

                        //  overwrite properties from env vars
                        //  if Properties has a property called 'foo-bar'
                        //  and the environment variable RH_FOO_BAR is defined
                        //  the value of the latter is used
                        p.replaceAll((k,v) -> {
                            if (k instanceof String sk) {
                                var vfe = valueFromEnv(sk, silent);
                                return vfe != null ? vfe : v;
                            } else {
                                return v;
                            }
                        });

                        final var writer = new StringWriter();
                        m.execute(writer, p);

                        Map<String, Object> confMap = new Yaml(new SafeConstructor()).load(writer.toString());
                        return new Configuration(overrideConfiguration(confMap, silent), confFilePath, silent);
                    } catch (FileNotFoundException fnfe) {
                        throw new ConfigurationException("Properties file not found: " + propFilePath, fnfe);
                    } catch (IOException ieo) {
                        throw new ConfigurationException("Error reading configuration file " + propFilePath, ieo);
                    }
                }
            }
        }
    }

    /**
     *
     * @param confMap
     * @return
     */
    private static Map<String, Object> overrideConfiguration(Map<String, Object> confMap, final boolean silent) {
        final var PROP_NAME = "RHO";

        var ctx = JXPathContext.newContext(confMap);
        ctx.setLenient(true);

        if (System.getenv().containsKey(PROP_NAME)) {
            var overrides = overrides(System.getenv().get(PROP_NAME), silent, silent);

            if (!silent) {
                LOGGER.info("Overriding configuration parameters from RHO environment variable:");
            }

            overrides.stream().forEachOrdered(o -> {
                if (!silent) {
                    LOGGER.info("\t{} -> {}", o.path(), o.value());
                }

                try {
                    createPathAndSetValue(ctx, o.path(), o.value());
                } catch(Throwable ise) {
                    LOGGER.error("Wrong configuration override {}, {}", o, ise.getMessage());
                }
            });
        } else {
            return confMap;
        }

        return confMap;
    }

    private static void createPathAndSetValue(JXPathContext ctx, String path, Object value) {
        createParents(ctx, path);
        ctx.createPathAndSetValue(path, value);
    }

    private static void createParents(JXPathContext ctx, String path) {
        if (path.lastIndexOf("/") == 0) {
            // root
            if (ctx.getValue(path) == null) {
                ctx.createPathAndSetValue(path, Maps.newLinkedHashMap());
            }
        } else {
            var parentPath = path.substring(0, path.lastIndexOf("/"));

            if (ctx.getValue(parentPath) == null) {
                createParents(ctx, parentPath);
                ctx.createPathAndSetValue(parentPath, Maps.newLinkedHashMap());
            }
        }
    }
}