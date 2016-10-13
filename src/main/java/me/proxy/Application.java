package me.proxy;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Parameters(separators = "=")
public class Application {
    static Logger logger = LoggerFactory.getLogger(Application.class);

    Map<String, TransportProvider.TransportProviderFactory> factoryMap;
    Application () {
        factoryMap = new HashMap<>();
        factoryMap.put("ssh", SSHTransportProvider.factory);
    }

    @Parameter(description = "proxies")
    private List<String> proxies = new ArrayList<>();

    @Parameter(names = "-debug", description = "debugging log")
    private boolean debug = false;

    @Parameter(names="-p", description="port for smart proxy")
    private int port=19999;

    @Parameter(names="-p2", description="port for proxy")
    private int port2=0;

    TransportProvider createProxy(String proxy) {
        try {
            URI proxyUri = new URI(proxy);
            TransportProvider.TransportProviderFactory factory = factoryMap.get(proxyUri.getScheme());
            if (factory != null) {
                return factory.create(proxyUri);
            }
        } catch (Exception ignore) {
        }

        return null;
    }

    void run() throws IOException {
        for (String proxy : proxies) {
            TransportProvider provider = createProxy(proxy);
            if (provider != null) {
                logger.info("Apply proxy: {}", proxy);
                ProxyService.addProxy(provider);
            }
        }
        logger.info("Bind at: {}", port);
        new Server(port, new Socks5Service(false), 50, 400).start();
        if (port2 == 0) {
            port2 = port + 1;
        }
        logger.info("Bind at: {}", port2);
        new Server(port2, new Socks5Service(true), 20, 100).start();
    }

    void configLogger() {
        if (debug) {
            final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            final Configuration config = ctx.getConfiguration();
            config.getRootLogger().setLevel(Level.DEBUG);
            ctx.updateLoggers();
        }
    }

    public static void main(String[] args) throws IOException {
        Application application = new Application();

        new JCommander(application, args);

        application.configLogger();
        application.run();
    }
}
