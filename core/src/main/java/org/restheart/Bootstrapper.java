/*-
 * ========================LICENSE_START=================================
 * restheart-core
 * %%
 * Copyright (C) 2014 - 2020 SoftInstigate
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * =========================LICENSE_END==================================
 */
package org.restheart;

import static io.undertow.Handlers.resource;
import static org.fusesource.jansi.Ansi.ansi;
import static org.fusesource.jansi.Ansi.Color.GREEN;
import static org.fusesource.jansi.Ansi.Color.RED;
import static org.restheart.ConfigurationKeys.STATIC_RESOURCES_MOUNT_EMBEDDED_KEY;
import static org.restheart.ConfigurationKeys.STATIC_RESOURCES_MOUNT_WELCOME_FILE_KEY;
import static org.restheart.ConfigurationKeys.STATIC_RESOURCES_MOUNT_WHAT_KEY;
import static org.restheart.ConfigurationKeys.STATIC_RESOURCES_MOUNT_WHERE_KEY;
import static org.restheart.exchange.Exchange.MAX_CONTENT_SIZE;
import static org.restheart.exchange.PipelineInfo.PIPELINE_TYPE.PROXY;
import static org.restheart.exchange.PipelineInfo.PIPELINE_TYPE.STATIC_RESOURCE;
import static org.restheart.handlers.PipelinedHandler.pipe;
import static org.restheart.handlers.injectors.RequestContentInjector.Policy.ON_REQUIRES_CONTENT_AFTER_AUTH;
import static org.restheart.handlers.injectors.RequestContentInjector.Policy.ON_REQUIRES_CONTENT_BEFORE_AUTH;
import static org.restheart.plugins.InitPoint.AFTER_STARTUP;
import static org.restheart.plugins.InitPoint.BEFORE_STARTUP;
import static org.restheart.plugins.InterceptPoint.REQUEST_AFTER_AUTH;
import static org.restheart.plugins.InterceptPoint.REQUEST_BEFORE_AUTH;
import static org.restheart.utils.PluginUtils.defaultURI;
import static org.restheart.utils.PluginUtils.initPoint;
import static org.restheart.utils.PluginUtils.uriMatchPolicy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.fusesource.jansi.AnsiConsole;
import org.restheart.exchange.Exchange;
import org.restheart.exchange.ExchangeKeys;
import org.restheart.exchange.PipelineInfo;
import org.restheart.graal.NativeImageBuildTimeChecker;
import org.restheart.handlers.ConfigurableEncodingHandler;
import org.restheart.handlers.ErrorHandler;
import org.restheart.handlers.PipelinedHandler;
import org.restheart.handlers.PipelinedWrappingHandler;
import org.restheart.handlers.ProxyExchangeBuffersCloser;
import org.restheart.handlers.QueryStringRebuilder;
import org.restheart.handlers.RequestInterceptorsExecutor;
import org.restheart.handlers.RequestLogger;
import org.restheart.handlers.RequestNotManagedHandler;
import org.restheart.handlers.TracingInstrumentationHandler;
import org.restheart.handlers.injectors.AuthHeadersRemover;
import org.restheart.handlers.injectors.ConduitInjector;
import org.restheart.handlers.injectors.PipelineInfoInjector;
import org.restheart.handlers.injectors.RequestContentInjector;
import org.restheart.handlers.injectors.XForwardedHeadersInjector;
import org.restheart.handlers.injectors.XPoweredByInjector;
import org.restheart.plugins.PluginRecord;
import org.restheart.plugins.PluginsRegistryImpl;
import org.restheart.plugins.RegisterPlugin;
import org.restheart.plugins.security.AuthMechanism;
import org.restheart.plugins.security.Authorizer;
import org.restheart.plugins.security.TokenManager;
import org.restheart.plugins.security.Authorizer.TYPE;
import org.restheart.security.handlers.SecurityHandler;
import org.restheart.utils.ConfigurationUtils;
import org.restheart.utils.FileUtils;
import org.restheart.utils.LoggingInitializer;
import org.restheart.utils.OSChecker;
import org.restheart.utils.PluginUtils;
import org.restheart.utils.RESTHeartDaemon;
import org.restheart.utils.ResourcesExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.SslClientAuthMode;
import org.xnio.Xnio;
import org.xnio.ssl.XnioSsl;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.handlers.AllowedMethodsHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.HttpContinueAcceptingHandler;
import io.undertow.server.handlers.RequestLimit;
import io.undertow.server.handlers.RequestLimitingHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.util.HttpString;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.restheart.utils.ConfigurationUtils.getOrDefault;
import static org.restheart.utils.BootstrapperUtils.*;


/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public final class Bootstrapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(Bootstrapper.class);

    private static boolean IS_FORKED;

    private static final Map<String, File> TMP_EXTRACTED_FILES = new HashMap<>();

    private static Path CONFIGURATION_FILE;
    private static Path PROPERTIES_FILE;
    private static boolean printConfiguration = false;
    private static boolean printConfigurationTemplate = false;

    private static GracefulShutdownHandler HANDLERS = null;
    private static Configuration configuration;
    private static Undertow undertowServer;

    private static final String EXITING = ", exiting...";
    private static final String RESTHEART = "RESTHeart";

    private Bootstrapper() {
    }

    /**
     *
     * @return the global configuration
     */
    public static Configuration getConfiguration() {
        return configuration;
    }

    private static void parseCommandLineParameters(final String[] args) {
        var parameters = new Args();
        var cmd = new CommandLine(parameters);

        try {
            cmd.parseArgs(args);
            if (cmd.isUsageHelpRequested()) {
                cmd.usage(System.out);
                System.exit(0);
            }

            if (cmd.isVersionHelpRequested()) {
                var version = Version.getInstance().getVersion() == null
                    ? "unknown (not packaged)"
                    : Version.getInstance().getVersion();

                System.out.println(RESTHEART
                    .concat(" Version ")
                    .concat(version)
                    .concat(" Build-Time ")
                    .concat(DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Version.getInstance().getBuildTime())));

                System.exit(0);
            }

            // print configuration options
            printConfiguration = parameters.printConfiguration;
            printConfigurationTemplate = parameters.printConfigurationTemplate;

            var confFilePath = (parameters.configPath == null)
                ? System.getenv("RESTHEART__CONFFILE")
                : parameters.configPath;
            CONFIGURATION_FILE = FileUtils.getFileAbsolutePath(confFilePath);

            FileUtils.getFileAbsolutePath(parameters.configPath);

            IS_FORKED = parameters.isForked;
            var propFilePath = (parameters.envFile == null)
                ? System.getenv("RESTHEART_ENVFILE")
                : parameters.envFile;

            PROPERTIES_FILE = FileUtils.getFileAbsolutePath(propFilePath);
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
            cmd.usage(System.out);
            System.exit(1);
        }
    }

    public static void main(final String[] args) throws ConfigurationException, IOException {
        parseCommandLineParameters(args);
        setJsonpathDefaults();
        configuration = Configuration.Builder.build(CONFIGURATION_FILE, PROPERTIES_FILE, true);
        run();
    }

    private static void run() {
        // we are at runtime. this is used for building native image
        NativeImageBuildTimeChecker.atRuntime();

        if (!configuration.isAnsiConsole()) {
            AnsiConsole.systemInstall();
        }

        if (!IS_FORKED) {
            initLogging(configuration, null, IS_FORKED);
            startServer(false);
        } else {
            if (OSChecker.isWindows()) {
                LOGGER.error("Fork is not supported on Windows");
                LOGGER.info(ansi().fg(GREEN).bold().a("RESTHeart stopped").reset().toString());
                System.exit(-1);
            }

            // RHSecDaemon only works on POSIX OSes
            final boolean isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

            if (!isPosix) {
                logErrorAndExit("Unable to fork process, this is only supported on POSIX compliant OSes", null, false, -1);
            }

            var d = new RESTHeartDaemon();
            if (d.isDaemonized()) {
                try {
                    d.init();
                    initLogging(configuration, d, IS_FORKED);
                } catch (Exception t) {
                    logErrorAndExit("Error staring forked process", t, false, false, -1);
                }
                startServer(true);
            } else {
                initLogging(configuration, d, IS_FORKED);
                try {
                    logStartMessages(configuration);
                    logLoggingConfiguration(configuration, false);
                    d.daemonize();
                } catch (Throwable t) {
                    logErrorAndExit("Error forking", t, false, false, -1);
                }
            }
        }
    }

    /**
     * Shutdown the server
     *
     * @param args command line arguments
     */
    public static void shutdown(final String[] args) {
        stopServer(false);
    }

    /**
     * startServer
     *
     * @param fork
     */
    private static void startServer(boolean fork) {
        logStartMessages(configuration);

        var pidFilePath = pidFile(CONFIGURATION_FILE, PROPERTIES_FILE);
        var pidFileAlreadyExists = false;

        if (!OSChecker.isWindows() && pidFilePath != null) {
            pidFileAlreadyExists = checkPidFile(CONFIGURATION_FILE, PROPERTIES_FILE);
        }

        logLoggingConfiguration(configuration, fork);

        // re-read configuration file, to log errors now that logger is initialized
        try {
            Configuration.Builder.build(CONFIGURATION_FILE, PROPERTIES_FILE, false);
        } catch (ConfigurationException ex) {
            logErrorAndExit(ex.getMessage() + EXITING, ex, false, -1);
        }

        // if -c, just print the effective configuration to sterr and exit
        if (printConfiguration) {
            LOGGER.info("Printing configuration and exiting");
            System.err.println(configuration.toString());
            System.exit(0);
        }

        // if -t, just print the configuration to sterr and exit
        if (printConfigurationTemplate) {
            try (var confFileStream = Configuration.class.getResourceAsStream("/restheart-default-config.yml")) {
                var content = new BufferedReader(new InputStreamReader(confFileStream, StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
                LOGGER.info("Printing configuration template and exiting");

                System.err.println(content);
                System.exit(0);
            } catch(IOException ioe) {
                logErrorAndExit(ioe.getMessage() + EXITING, ioe, false, -1);
            }
        }

        // force instantiation of all plugins singletons
        try {
            PluginsRegistryImpl.getInstance().instantiateAll();
        } catch (IllegalArgumentException iae) {
            // this occurs instatiating plugin missing external dependencies
            // unfortunatly Classgraph wraps it to IllegalArgumentException
            if (iae.getMessage() != null && iae.getMessage().contains("NoClassDefFoundError")) {
                logErrorAndExit("Error instantiating plugins: an external dependency is missing. Copy the missing dependency jar to the plugins directory to add it to the classpath", iae, false, -112);
            } else {
                logErrorAndExit("Error instantiating plugins", iae, false, -110);
            }
        } catch (NoClassDefFoundError ncdfe) {
            // this occurs instatiating plugin missing external dependencies
            // unfortunatly Classgraph wraps it to IllegalArgumentException

            logErrorAndExit("Error instantiating plugins: an external dependency is missing. Copy the missing dependency jar to the plugins directory to add it to the classpath", ncdfe, false, -112);
        } catch (LinkageError le) {
            // this occurs executing plugin code compiled
            // with wrong version of restheart-commons

            var version = Version.getInstance().getVersion() == null ? "of correct version" : "v" + Version.getInstance().getVersion();

            logErrorAndExit("Linkage error instantiating plugins. Check that all plugins were compiled against restheart-commons " + version, le, false, -111);
        } catch (Throwable t) {
            logErrorAndExit("Error instantiating plugins", t, false, -110);
        }

        // run pre startup initializers
        PluginsRegistryImpl.getInstance()
            .getInitializers()
            .stream()
            .filter(i -> initPoint(i.getInstance()) == BEFORE_STARTUP)
            .forEach(i -> {
                try {
                    i.getInstance().init();
                } catch (NoClassDefFoundError iae) {
                    // this occurs executing interceptors missing external dependencies
                    LOGGER.error("Error executing initializer {}. An external dependency is missing. Copy the missing dependency jar to the plugins directory to add it to the classpath", i.getName(), iae);
                } catch (LinkageError le) {
                    // this might occur executing plugin code compiled
                    // with wrong version of restheart-commons
                    var version = Version.getInstance().getVersion() == null
                        ? "of correct version"
                        : "v" + Version.getInstance().getVersion();

                    LOGGER.error("Linkage error executing initializer {}. Check that it was compiled against restheart-commons {}", i.getName(), version, le);
                } catch (Throwable t) {
                    LOGGER.error("Error executing initializer {}", i.getName());
                }
            });
        try {
            startCoreSystem();
        } catch (Throwable t) {
            logErrorAndExit("Error starting RESTHeart. Exiting...", t, false, !pidFileAlreadyExists, -2);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopServer(false)));

        // create pid file on supported OSes
        if (!OSChecker.isWindows() && pidFilePath != null) {
            FileUtils.createPidFile(pidFilePath);
            LOGGER.info("Pid file {}", pidFilePath);
        }

        // run initializers
        PluginsRegistryImpl.getInstance()
            .getInitializers()
            .stream()
            .filter(i -> initPoint(i.getInstance()) == AFTER_STARTUP)
            .forEach(i -> {
                try {
                    i.getInstance().init();
                } catch (NoClassDefFoundError iae) {
                    // this occurs executing interceptors missing external dependencies

                    LOGGER.error("Error executing initializer {}. An external dependency is missing. Copy the missing dependency jar to the plugins directory to add it to the classpath", i.getName(), iae);
                } catch (LinkageError le) {
                    // this might occur executing plugin code compiled
                    // with wrong version of restheart-commons

                    var version = Version.getInstance().getVersion() == null
                            ? "of correct version"
                            : "v" + Version.getInstance().getVersion();

                    LOGGER.error("Linkage error executing initializer {}. Check that it was compiled against restheart-commons {}", i.getName(), version, le);
                } catch (Throwable t) {
                    LOGGER.error("Error executing initializer {}", i.getName(), t);
                }
            });

        LOGGER.info(ansi().fg(GREEN).bold().a("RESTHeart started").reset().toString());
    }

    /**
     * stopServer
     *
     * @param silent
     */
    private static void stopServer(boolean silent) {
        stopServer(silent, true);
    }

    /**
     * stopServer
     *
     * @param silent
     * @param removePid
     */
    private static void stopServer(boolean silent, boolean removePid) {
        if (!silent) {
            LOGGER.info("Stopping RESTHeart...");
        }

        if (HANDLERS != null) {
            if (!silent) {
                LOGGER.info("Waiting for pending request to complete (up to 1 minute)...");
            }
            try {
                HANDLERS.shutdown();
                HANDLERS.awaitShutdown(60 * 1000); // up to 1 minute
            } catch (InterruptedException ie) {
                LOGGER.error("Error while waiting for pending request to complete", ie);
                Thread.currentThread().interrupt();
            }
        }

        var pidFilePath = FileUtils.getPidFilePath(FileUtils.getFileAbsolutePathHash(CONFIGURATION_FILE, PROPERTIES_FILE));

        if (removePid && pidFilePath != null) {
            if (!silent) {
                LOGGER.info("Removing the pid file {}", pidFilePath.toString());
            }
            try {
                Files.deleteIfExists(pidFilePath);
            } catch (IOException ex) {
                LOGGER.error("Can't delete pid file {}", pidFilePath.toString(), ex);
            }
        }

        if (!silent) {
            LOGGER.info("Cleaning up temporary directories...");
        }
        TMP_EXTRACTED_FILES.keySet().forEach(k -> {
            try {
                ResourcesExtractor.deleteTempDir(Bootstrapper.class, k, TMP_EXTRACTED_FILES.get(k));
            } catch (URISyntaxException | IOException ex) {
                LOGGER.error("Error cleaning up temporary directory {}", TMP_EXTRACTED_FILES.get(k).toString(), ex);
            }
        });

        if (undertowServer != null) {
            undertowServer.stop();
        }

        if (!silent) {
            LOGGER.info(ansi().fg(GREEN).bold().a("RESTHeart stopped").reset().toString());
        }

        LoggingInitializer.stopLogging();
    }

    /**
     * startCoreSystem
     */
    private static void startCoreSystem() {
        if (configuration == null) {
            logErrorAndExit("No configuration found. exiting..", null, false, -1);
        }

        if (!configuration.isHttpsListener() && !configuration.isHttpListener() && !configuration.isAjpListener()) {
            logErrorAndExit("No listener specified. exiting..", null, false, -1);
        }

        final var tokenManager = PluginsRegistryImpl.getInstance().getTokenManager();

        final var authMechanisms = PluginsRegistryImpl.getInstance().getAuthMechanisms();

        if (authMechanisms == null || authMechanisms.isEmpty()) {
            LOGGER.warn(ansi().fg(RED).bold().a("No Authentication Mechanisms defined").reset().toString());
        }

        final var authorizers = PluginsRegistryImpl.getInstance().getAuthorizers();

        final var allowers = authorizers == null
            ? null
            : authorizers.stream()
                .filter(a -> a.isEnabled())
                .filter(a -> a.getInstance() != null)
                .map(a -> a.getInstance())
                .filter(a -> PluginUtils.authorizerType(a) == TYPE.ALLOWER)
                .collect(Collectors.toList());

        if (allowers == null || allowers.isEmpty()) {
            LOGGER.warn(ansi().fg(RED).bold().a("No Authorizer of type ALLOWER defined, all requests to secured services will be forbidden; fullAuthorizer can be enabled to allow any request.").reset().toString());
        }

        var builder = Undertow.builder();

        if (configuration.isHttpsListener()) {
            builder.addHttpsListener(configuration.getHttpsPort(), configuration.getHttpsHost(), initSSLContext());
            if (configuration.getHttpsHost().equals("127.0.0.1") || configuration.getHttpsHost().equalsIgnoreCase("localhost")) {
                LOGGER.warn("HTTPS listener bound to localhost:{}. Remote systems will be unable to connect to this server.", configuration.getHttpsPort());
            } else {
                LOGGER.info("HTTPS listener bound at {}:{}", configuration.getHttpsHost(), configuration.getHttpsPort());
            }
        }

        if (configuration.isHttpListener()) {
            builder.addHttpListener(configuration.getHttpPort(), configuration.getHttpHost());

            if (configuration.getHttpHost().equals("127.0.0.1") || configuration.getHttpHost().equalsIgnoreCase("localhost")) {
                LOGGER.warn("HTTP listener bound to localhost:{}. Remote systems will be unable to connect to this server.", configuration.getHttpPort());
            } else {
                LOGGER.info("HTTP listener bound at {}:{}", configuration.getHttpHost(), configuration.getHttpPort());
            }
        }

        if (configuration.isAjpListener()) {
            builder.addAjpListener(configuration.getAjpPort(), configuration.getAjpHost());

            if (configuration.getAjpHost().equals("127.0.0.1") || configuration.getAjpHost().equalsIgnoreCase("localhost")) {
                LOGGER.warn("AJP listener bound to localhost:{}. Remote systems will be unable to connect to this server.", configuration.getAjpPort());
            } else {
                LOGGER.info("AJP listener bound at {}:{}", configuration.getAjpHost(), configuration.getAjpPort());
            }
        }

        HANDLERS = getPipeline(authMechanisms, authorizers, tokenManager);

        // update buffer size in
        Exchange.updateBufferSize(configuration.getBufferSize());

        builder = builder
            .setIoThreads(configuration.getIoThreads())
            .setWorkerThreads(configuration.getWorkerThreads())
            .setDirectBuffers(configuration.isDirectBuffers())
            .setBufferSize(configuration.getBufferSize())
            .setHandler(HANDLERS);

        // starting from undertow 1.4.23 URL checks become much stricter
        // (undertow commit 09d40a13089dbff37f8c76d20a41bf0d0e600d9d)
        // allow unescaped chars in URL (otherwise not allowed by default)
        builder.setServerOption(UndertowOptions.ALLOW_UNESCAPED_CHARACTERS_IN_URL, configuration.isAllowUnescapedCharactersInUrl());

        LOGGER.debug("Allow unescaped characters in URL: {}", configuration.isAllowUnescapedCharactersInUrl());

        ConfigurationUtils.setConnectionOptions(builder, configuration);

        undertowServer = builder.build();
        undertowServer.start();
    }

    private static SSLContext initSSLContext() {
        try {
            var sslContext = SSLContext.getInstance("TLS");

            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

            var ks = KeyStore.getInstance(KeyStore.getDefaultType());

            if (configuration.getKeystoreFile() != null && configuration.getKeystorePassword() != null && configuration.getCertPassword() != null) {
                try (var fis = new FileInputStream(new File(configuration.getKeystoreFile()))) {
                    ks.load(fis, configuration.getKeystorePassword().toCharArray());
                    kmf.init(ks, configuration.getCertPassword().toCharArray());
                }
            } else {
                logErrorAndExit("Cannot enable the HTTPS listener: the keystore is not configured. Generate a keystore and set the configuration options keystore-file, keystore-password and certpassword. More information at https://restheart.org/docs/security/tls/", null, false, -1);
            }

            tmf.init(ks);

            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            return sslContext;
        } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException | CertificateException | UnrecoverableKeyException ex) {
            logErrorAndExit("Couldn't start RESTHeart, error with specified keystore. Check the keystore-file, keystore-password and certpassword options. Exiting..", ex, false, -1);
        } catch (FileNotFoundException ex) {
            logErrorAndExit("Couldn't start RESTHeart, keystore file not found. Check the keystore-file, keystore-password and certpassword options. Exiting..", ex, false, -1);
        } catch (IOException ex) {
            logErrorAndExit("Couldn't start RESTHeart, error reading the keystore file. Check the keystore-file, keystore-password and certpassword options. Exiting..", ex, false, -1);
        }

        return null;
    }

    /**
     * logErrorAndExit
     *
     * @param message
     * @param t
     * @param silent
     * @param status
     */
    private static void logErrorAndExit(String message, Throwable t, boolean silent, int status) {
        logErrorAndExit(message, t, silent, true, status);
    }

    /**
     * getHandlersPipe
     *
     * @param identityManager
     * @param authorizers
     * @param tokenManager
     * @return a GracefulShutdownHandler
     */
    private static GracefulShutdownHandler getPipeline(final Set<PluginRecord<AuthMechanism>> authMechanisms, final Set<PluginRecord<Authorizer>> authorizers, final PluginRecord<TokenManager> tokenManager) {
        PluginsRegistryImpl
            .getInstance()
            .getRootPathHandler()
            .addPrefixPath("/", new RequestNotManagedHandler());

        LOGGER.debug("Content buffers maximun size is {} bytes", MAX_CONTENT_SIZE);

        plugServices();

        plugProxies(configuration, authMechanisms, authorizers, tokenManager);

        plugStaticResourcesHandlers(configuration);

        return getBasePipeline();
    }

    /**
     *
     * @return the base handler pipeline
     */
    private static GracefulShutdownHandler getBasePipeline() {
        return new GracefulShutdownHandler(
            new RequestLimitingHandler(
                new RequestLimit(configuration.getRequestsLimit()),
                new AllowedMethodsHandler(
                    new BlockingHandler(
                            new ErrorHandler(
                                    new HttpContinueAcceptingHandler(PluginsRegistryImpl.getInstance().getRootPathHandler()))),
                    // allowed methods
                    HttpString.tryFromString(ExchangeKeys.METHOD.GET.name()),
                    HttpString.tryFromString(ExchangeKeys.METHOD.POST.name()),
                    HttpString.tryFromString(ExchangeKeys.METHOD.PUT.name()),
                    HttpString.tryFromString(ExchangeKeys.METHOD.DELETE.name()),
                    HttpString.tryFromString(ExchangeKeys.METHOD.PATCH.name()),
                    HttpString.tryFromString(ExchangeKeys.METHOD.OPTIONS.name()))));
    }

    /**
     * plug services
     */
    private static void plugServices() {
        PluginsRegistryImpl.getInstance().getServices().stream()
        // if a service has been added programmatically (for instance, by an initializer)
        // filter out it (assuming it isn't annotated with @RegisterPlugin)
        .filter(srv -> srv.getInstance().getClass().getDeclaredAnnotation(RegisterPlugin.class) != null)
        .forEach(srv -> {
            var srvConfArgs = srv.getConfArgs();

            String uri;
            var mp = uriMatchPolicy(srv.getInstance());

            if (srvConfArgs == null || !srvConfArgs.containsKey("uri") || srvConfArgs.get("uri") == null) {
                uri = defaultURI(srv.getInstance());
            } else {
                if (!(srvConfArgs.get("uri") instanceof String)) {
                    LOGGER.error("Cannot start service {}: the configuration property 'uri' must be a string", srv.getName());

                    return;
                } else {
                    uri = (String) srvConfArgs.get("uri");
                }
            }

            if (uri == null) {
                LOGGER.error("Cannot start service {}: the configuration property 'uri' is not defined and the service does not have a default value", srv.getName());
                return;
            }

            if (!uri.startsWith("/")) {
                LOGGER.error("Cannot start service {}: the configuration property 'uri' must start with /", srv.getName(), uri);

                return;
            }

            var secured = srv.isSecure();

            PluginsRegistryImpl.getInstance().plugService(srv, uri, mp, secured);

            LOGGER.info(ansi().fg(GREEN).a("URI {} bound to service {}, secured: {}, uri match {}").reset().toString(), uri, srv.getName(), secured, mp);
        });
    }

    /**
     * plugProxies
     *
     * @param conf
     * @param paths
     * @param authMechanisms
     * @param identityManager
     * @param authorizers
     */
    private static void plugProxies(final Configuration conf, final Set<PluginRecord<AuthMechanism>> authMechanisms, final Set<PluginRecord<Authorizer>> authorizers, final PluginRecord<TokenManager> tokenManager) {
        if (conf.getProxies() == null || conf.getProxies().isEmpty()) {
            LOGGER.debug("No {} specified", ConfigurationKeys.PROXY_KEY);
            return;
        }

        conf.getProxies().stream().forEachOrdered((Map<String, Object> proxies) -> {
            String location = getOrDefault(proxies, ConfigurationKeys.PROXY_LOCATION_KEY, null, true);

            var _proxyPass = getOrDefault(proxies, ConfigurationKeys.PROXY_PASS_KEY, null, true);

            if (location == null && _proxyPass != null) {
                LOGGER.warn("Location URI not specified for resource {} ", _proxyPass);
                return;
            }

            if (location == null && _proxyPass == null) {
                LOGGER.warn("Invalid proxies entry detected");
                return;
            }

            // The number of connections to create per thread
            var connectionsPerThread = getOrDefault(proxies, ConfigurationKeys.PROXY_CONNECTIONS_PER_THREAD, 10, true);

            var maxQueueSize = getOrDefault(proxies, ConfigurationKeys.PROXY_MAX_QUEUE_SIZE, 0, true);

            var softMaxConnectionsPerThread = getOrDefault(proxies, ConfigurationKeys.PROXY_SOFT_MAX_CONNECTIONS_PER_THREAD, 5, true);

            var ttl = getOrDefault(proxies, ConfigurationKeys.PROXY_TTL, -1, true);

            var rewriteHostHeader = getOrDefault(proxies, ConfigurationKeys.PROXY_REWRITE_HOST_HEADER, true, true);

            // Time in seconds between retries for problem server
            var problemServerRetry = getOrDefault(proxies, ConfigurationKeys.PROXY_PROBLEM_SERVER_RETRY, 10, true);

            String name = getOrDefault(proxies, ConfigurationKeys.PROXY_NAME, null, true);

            final var xnio = Xnio.getInstance();

            final var optionMap = OptionMap.create(Options.SSL_CLIENT_AUTH_MODE, SslClientAuthMode.REQUIRED, Options.SSL_STARTTLS, true);

            XnioSsl sslProvider = null;

            try {
                sslProvider = xnio.getSslProvider(optionMap);
            } catch (GeneralSecurityException ex) {
                logErrorAndExit("error configuring ssl", ex, false, -13);
            }

            try {
                var proxyClient = new LoadBalancingProxyClient()
                    .setConnectionsPerThread(connectionsPerThread)
                    .setSoftMaxConnectionsPerThread(softMaxConnectionsPerThread)
                    .setMaxQueueSize(maxQueueSize)
                    .setProblemServerRetry(problemServerRetry)
                    .setTtl(ttl);

                if (_proxyPass instanceof String __proxyPass) {
                    proxyClient = proxyClient.addHost(new URI(__proxyPass), sslProvider);
                } else if (_proxyPass instanceof List<?> __proxyPass) {
                    for (var proxyPassURL : __proxyPass) {
                        if (proxyPassURL instanceof String _proxyPassURL) {
                            proxyClient = proxyClient.addHost(new URI(_proxyPassURL), sslProvider);
                        } else {
                            LOGGER.warn("Invalid proxy pass URL {}, location {} not bound ", proxyPassURL, location);
                        }
                    }
                } else {
                    LOGGER.warn("Invalid proxy pass URL {}, location {} not bound ", _proxyPass);
                }

                ProxyHandler proxyHandler = ProxyHandler.builder()
                    .setRewriteHostHeader(rewriteHostHeader)
                    .setProxyClient(proxyClient)
                    .build();

                var proxy = pipe(
                    new PipelineInfoInjector(),
                    new TracingInstrumentationHandler(),
                    new RequestLogger(),
                    new ProxyExchangeBuffersCloser(),
                    new XPoweredByInjector(),
                    new RequestContentInjector(ON_REQUIRES_CONTENT_BEFORE_AUTH),
                    new RequestInterceptorsExecutor(REQUEST_BEFORE_AUTH),
                    new QueryStringRebuilder(),
                    new SecurityHandler(authMechanisms, authorizers, tokenManager),
                    new AuthHeadersRemover(),
                    new XForwardedHeadersInjector(),
                    new RequestContentInjector(ON_REQUIRES_CONTENT_AFTER_AUTH),
                    new RequestInterceptorsExecutor(REQUEST_AFTER_AUTH),
                    new QueryStringRebuilder(),
                    new ConduitInjector(),
                    PipelinedWrappingHandler.wrap(new ConfigurableEncodingHandler(proxyHandler))); // Must be after ConduitInjector

                PluginsRegistryImpl.getInstance().plugPipeline(location, proxy, new PipelineInfo(PROXY, location, name));

                LOGGER.info(ansi().fg(GREEN).a("URI {} bound to proxy resource {}").reset().toString(), location, _proxyPass);
            } catch (URISyntaxException ex) {
                LOGGER.warn("Invalid location URI {}, resource {} not bound ", location, _proxyPass);
            }
        });
    }

    /**
     * plugStaticResourcesHandlers
     *
     * plug the static resources specified in the configuration file
     *
     * @param conf
     * @param pathHandler
     * @param authenticationMechanism
     * @param identityManager
     * @param accessManager
     */
    private static void plugStaticResourcesHandlers(final Configuration conf) {
        if (conf.getStaticResourcesMounts() == null || conf.getStaticResourcesMounts().isEmpty()) {
            LOGGER.debug("No {} specified", ConfigurationKeys.STATIC_RESOURCES_MOUNT_EMBEDDED_KEY);
            return;
        }

        conf.getStaticResourcesMounts().stream().forEach(sr -> {
            try {
                var path = (String) sr.get(STATIC_RESOURCES_MOUNT_WHAT_KEY);
                var where = (String) sr.get(STATIC_RESOURCES_MOUNT_WHERE_KEY);
                var welcomeFile = (String) sr.get(STATIC_RESOURCES_MOUNT_WELCOME_FILE_KEY);

                var embedded = (Boolean) sr.get(STATIC_RESOURCES_MOUNT_EMBEDDED_KEY);
                if (embedded == null) {
                    embedded = false;
                }

                if (where == null || !where.startsWith("/")) {
                    LOGGER.error("Cannot bind static resources to {}. parameter 'where' must start with /", where);
                    return;
                }

                if (welcomeFile == null) {
                    welcomeFile = "index.html";
                }

                File file;

                if (embedded) {
                    if (path.startsWith("/")) {
                        LOGGER.error("Cannot bind embedded static resources to {}. parameter 'where'"
                                + "cannot start with /. the path is relative to the jar root dir"
                                + " or classpath directory", where);
                        return;
                    }

                    try {
                        file = ResourcesExtractor.extract(Bootstrapper.class, path);

                        if (ResourcesExtractor.isResourceInJar(Bootstrapper.class, path)) {
                            TMP_EXTRACTED_FILES.put(path, file);
                            LOGGER.info("Embedded static resources {} extracted in {}", path, file.toString());
                        }
                    } catch (URISyntaxException | IOException | IllegalStateException ex) {
                        LOGGER.error("Error extracting embedded static resource {}", path, ex);
                        return;
                    }
                } else if (!path.startsWith("/")) {
                    // this is to allow specifying the configuration file path relative
                    // to the jar (also working when running from classes)
                    var location = Bootstrapper.class.getProtectionDomain().getCodeSource().getLocation();

                    var locationFile = new File(location.getPath());

                    var _path = Paths.get(locationFile.getParent().concat(File.separator).concat(path));

                    // normalize addresses https://issues.jboss.org/browse/UNDERTOW-742
                    file = _path.normalize().toFile();
                } else {
                    file = new File(path);
                }

                if (file.exists()) {
                    var handler = resource(new FileResourceManager(file, 3)).addWelcomeFiles(welcomeFile).setDirectoryListingEnabled(false);

                    var ph = PipelinedHandler.pipe(new PipelineInfoInjector(), new RequestLogger(), PipelinedWrappingHandler.wrap(handler));

                    PluginsRegistryImpl.getInstance().plugPipeline(where, ph, new PipelineInfo(STATIC_RESOURCE, where, path));

                    LOGGER.info(ansi().fg(GREEN).a("URI {} bound to static resource {}").reset().toString(), where, file.getAbsolutePath());
                } else {
                    LOGGER.error("Failed to bind URL {} to static resources {}. Directory does not exist.", where, path);
                }

            } catch (Throwable t) {
                LOGGER.error("Cannot bind static resources to {}", sr.get(STATIC_RESOURCES_MOUNT_WHERE_KEY), t);
            }
        });
    }

    /**
     * logErrorAndExit
     *
     * @param message
     * @param t
     * @param silent
     * @param removePid
     * @param status
     */
    private static void logErrorAndExit(String message, Throwable t, boolean silent, boolean removePid, int status) {
        if (t == null) {
            LOGGER.error(message);
        } else {
            LOGGER.error(message, t);
        }
        stopServer(silent, removePid);
        System.exit(status);
    }

    @Command(name="java -jar restheart.jar")
    private static class Args {
        @Parameters(index = "0", arity = "0..1", paramLabel = "FILE", description = "Main configuration file")
        private String configPath = null;

        @Option(names = "--fork", description = "Fork the process in background")
        private boolean isForked = false;

        @Option(names = {"-e", "--envFile", "--envfile"}, description = "Environment file name")
        private String envFile = null;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "This help message")
        private boolean help = false;

        @Option(names = {"-c", "--printConfiguration"}, description = "Print the effective configuration to the standard error and exit")
        private boolean printConfiguration = false;

        @Option(names = {"-t", "--printConfigurationTemplate"}, description = "Print the configuration template to the standard error and exit")
        private boolean printConfigurationTemplate = false;

        @Option(names = { "-v", "--version" }, versionHelp = true, description = "Print product version to the output stream and exit")
        boolean versionRequested;
    }
}
