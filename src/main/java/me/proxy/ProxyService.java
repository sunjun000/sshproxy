package me.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyService {
    static Logger logger = LoggerFactory.getLogger(ProxyService.class);
    private static class DirectTransportProvider implements TransportProvider {
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getName() {
            return "direct";
        }

        public Transport connect(String host, int port, int timeout) throws IOException {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), timeout);
            return new SocketTransport(socket);
        }
    }

    private static class SocketTransport implements TransportProvider.Transport {
        private Socket socket;
        public SocketTransport(Socket socket) {
            this.socket = socket;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }

        @Override
        public String toString() {
            return socket.toString();
        }
    }

    private static TransportProvider directTransportProvider = new DirectTransportProvider();
    private static HashMap<String, TransportProvider> proxyProviders = new LinkedHashMap<>();
    private static Map<String, TransportProvider> preferredProviders = new ConcurrentHashMap<>();

    public static void addProxy(TransportProvider transportProvider) {
        proxyProviders.put(transportProvider.getName(), transportProvider);
    }

    public static void setPreferredProxy(String host, String proxyName) {
        TransportProvider transportProvider = proxyProviders.get(proxyName);
        if (transportProvider != null) {
            preferredProviders.put(host, transportProvider);
        }
    }

    private static final int TIMEOUT = 5000;
    public static void proxy(Socket socket, String host, int port, boolean tryDirectConnection) throws IOException {
        boolean requireResponse = port == 80; // http connection should have response // handle gfw reset
        Piper piper = new Piper(new SocketTransport(socket));

        Map<String, IOException> errors = new LinkedHashMap<>();

        TransportProvider preferredProvider = preferredProviders.get(host);
        if (preferredProvider != null && preferredProvider.isAvailable()) {
            try {
                logger.debug("try preferred proxy <{}> to {}:{}", preferredProvider.getName(), host, port);
                TransportProvider.Transport proxyTransport = preferredProvider.connect(host, port, TIMEOUT);
                try {
                    if (piper.pipe(proxyTransport) || !requireResponse) {
                        return;
                    }
                } finally {
                    proxyTransport.close();
                }
            } catch (IOException e) {
                errors.put(preferredProvider.getName(), e);
            }
            preferredProviders.remove(host);
        }

        if (tryDirectConnection) {
            try {
                logger.debug("try direct connection to {}:{}", host, port);
                TransportProvider.Transport proxyTransport = directTransportProvider.connect(host, port, TIMEOUT);
                try {
                    if (piper.pipe(proxyTransport) || !requireResponse) {
                        return;
                    }
                } finally {
                    proxyTransport.close();
                }
            } catch (IOException e) {
                errors.put("direct", e);
            }
        }

        for(TransportProvider proxyProvider: proxyProviders.values()) {
            if (!proxyProvider.isAvailable() || proxyProvider.equals(preferredProvider)) {
                continue;
            }

            try {
                logger.debug("try proxy <{}> to {}:{}", proxyProvider.getName(), host, port);
                TransportProvider.Transport proxyTransport = proxyProvider.connect(host, port, TIMEOUT);
                preferredProviders.put(host, proxyProvider);
                try {
                    if (piper.pipe(proxyTransport) || !requireResponse) {
                        return;
                    }
                } finally {
                    proxyTransport.close();
                }
                preferredProviders.remove(host);
            } catch (IOException e) {
                errors.put(proxyProvider.getName(), e);
            }
        }

        logger.error("no proxy to {}:{}", host, port);
        if (logger.isDebugEnabled()) {
            for (Map.Entry<String, IOException> entry : errors.entrySet()) {
                logger.debug("connection : " + entry.getKey() + " error:", entry.getValue());
            }
        }
    }

    private static class Piper {
        private TransportProvider.Transport localTransport;
        private long lastReadTime = 0;
        private boolean running = false;
        private ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private long[] size = new long[]{0, 0};
        private Piper(TransportProvider.Transport localTransport) {
            this.localTransport = localTransport;
        }

        public boolean pipe(final TransportProvider.Transport proxyTransport) {
            if (size[1] > 0) {
                logger.error("pipe: {} --> {} is in a error state", localTransport, proxyTransport);
                throw new RuntimeException("pipe: " + localTransport + " --> " + proxyTransport + " is in a error state");
            }
            running = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    forward(proxyTransport, localTransport);
                }
            });
            thread.setName(Thread.currentThread().getName() + " -- peer");
            thread.setDaemon(true);
            thread.start();
            forward(localTransport, proxyTransport);

            logger.debug("pipe: {} --> {} OUT:{} IN:{}", localTransport, proxyTransport, size[0], size[1]);

            return size[0] == 0 || size[1] > 0;
        }

        private static final int BUFF_SIZE = 4096;
        private void forward(TransportProvider.Transport from, TransportProvider.Transport to) {
            try {
                byte[] buff = new byte[BUFF_SIZE];

                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();

                if(localTransport.equals(from)) {
                    synchronized (this) {
                        out.write(buf.toByteArray());
                        out.flush();
                    }
                }

                lastReadTime = System.currentTimeMillis();
                int len = 0;
                while (len >= 0 && running) {
                    try {
                        len = in.read(buff);
                    } catch (SocketTimeoutException ignore) {
                        len = 0;
                    }

                    if (len > 0) {
                        lastReadTime = System.currentTimeMillis();
                        if(localTransport.equals(from)) {
                            if (size[1] == 0) {
                                buf.write(buff, 0, len);
                            }
                            size[0] += len;
                        } else {
                            size[1] += len;
                        }
                        out.write(buff, 0, len);
                        out.flush();
                    } else {
                        if(System.currentTimeMillis() - lastReadTime > 10 * 60 * 1000) {
                            break;
                        }
                    }
                }
            } catch (SocketException e) {
                logger.debug("pipe closed: " + from + " --> " + to);
            } catch (IOException e) {
                logger.error("pipe error: " + from + " --> " + to, e);
            } finally {
                try {
                    if (size[1] > 0 || !localTransport.equals(from)) {
                        from.close();
                    }
                } catch (IOException ignore) {
                }
                try {
                    if (size[1] > 0 || !localTransport.equals(to)) {
                        to.close();
                    }
                } catch (IOException ignore) {
                }
            }

            running = false;
        }

    }
}
