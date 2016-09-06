package me.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

public interface TransportProvider {
    interface Transport {
        InputStream getInputStream() throws IOException;

        OutputStream getOutputStream() throws IOException;

        void close() throws IOException;
    }

    interface TransportProviderFactory {
        TransportProvider create(URI proxyUri);
    }

    boolean isAvailable();

    String getName();

    Transport connect(String host, int port, int timeout) throws IOException;
}