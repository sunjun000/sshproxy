package me.proxy;

import com.jcraft.jsch.*;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SSHTransportProvider implements TransportProvider {
    public static final TransportProviderFactory factory =
            new TransportProviderFactory() {
                @Override
                public TransportProvider create(URI proxyUri) {
                    return new SSHTransportProvider(proxyUri);
                }
            };

    static class AllowAllHostKeyRepository implements HostKeyRepository {

        @Override
        public int check(String host, byte[] key) {
            return OK;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {

        }

        @Override
        public void remove(String host, String type) {

        }

        @Override
        public void remove(String host, String type, byte[] key) {

        }

        @Override
        public String getKnownHostsRepositoryID() {
            return getClass().getSimpleName();
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    Session currentSession = null;
    private String host;
    private int port;
    private String user;
    private String password;
    private JSch jsch;
    private String name;

    private Map<Session, List<ChannelTransport>> sessions;

    public SSHTransportProvider(URI uri) {
        this("ssh@" + uri.getHost(), uri);
    }

    public SSHTransportProvider(String name, URI uri) {
        this.jsch=new JSch();
        this.jsch.setHostKeyRepository(new AllowAllHostKeyRepository());
        this.name = name;
        this.host = uri.getHost();
        this.port = uri.getPort();
        if (this.port == -1) {
            this.port = 22;
        }

        String[] parts = uri.getUserInfo().split(":", 2);
        this.user = parts[0];
        this.password = parts.length == 2?parts[1]:"";

        this.sessions = new ConcurrentHashMap<>();
        try {
            getSession(true);
        } catch (JSchException ignore) {
        }

        initJMX();
    }

    public interface SSHProviderMXBean {
        List<Integer> getSessions();
    }

    public static class SSHProvider implements SSHProviderMXBean {
        private SSHTransportProvider provider;
        public SSHProvider(SSHTransportProvider provider) {
            this.provider = provider;
        }

        public List<Integer> getSessions() {
            return provider.sessions.values().stream().map(List::size).collect(Collectors.toList());
        }
    }

    private void initJMX() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("me.proxy:type=" + this.getName());
            SSHProvider mbean = new SSHProvider(this);
            mbs.registerMBean(mbean, name);
        } catch (Exception ignore) {
        }
    }

    private void tryCloseSession(Session session) {
        List<ChannelTransport> channelTransports = sessions.get(session);

        boolean canBeDisconnected = !session.equals(currentSession);

        if (canBeDisconnected) {
            if (channelTransports != null && channelTransports.size() > 0) {
                LinkedList<ChannelTransport> copy = new LinkedList<>(channelTransports);
                for (ChannelTransport channelTransport : copy) {
                    if (channelTransport.channel.isConnected()) {
                        canBeDisconnected = false;
                        channelTransports.remove(channelTransport);
                    }
                }
            }
        }

        if (canBeDisconnected) {
            session.disconnect();
            sessions.remove(session);
        }
    }

    private void closeSocket(ChannelTransport socket) {
        Session session = null;
        try {
            session = socket.channel.getSession();
        } catch (JSchException ignore) {
        }
        socket.channel.disconnect();
        List<ChannelTransport> channelTransports = sessions.get(session);
        if (channelTransports != null) {
            channelTransports.remove(socket);
        }
        tryCloseSession(session);
    }

    private Session createSession() throws JSchException {
        Session session = jsch.getSession(user, host, port);
        session.setPassword(password);
        session.setTimeout(15000);
        session.setDaemonThread(true);
        session.connect(3000);
        sessions.put(session, new LinkedList<ChannelTransport>());
        return session;
    }

    private synchronized Session getSession(boolean forceCreate) throws JSchException {
        if (currentSession != null && currentSession.isConnected() && !forceCreate) {
            return currentSession;
        }

        Session oldSession = currentSession;
        currentSession = createSession();
        if (oldSession != null) {
            tryCloseSession(oldSession);
        }

        return currentSession;
    }

    private boolean isServerAvailable() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.close();
            return true;
        } catch (IOException ignore) {
        }
        return false;
    }

    public boolean isAvailable() {
        if (currentSession != null && currentSession.isConnected()) {
            return true;
        } else {
            return isServerAvailable();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    private Transport connect(Session session, String host, int port, int timeout) throws JSchException, IOException {
        Channel channel = session.getStreamForwarder(host, port);
        channel.connect(timeout);
        ChannelTransport socket = new ChannelTransport(channel, this, host, port);
        List<ChannelTransport> channelTransports = sessions.get(session);
        channelTransports.add(socket);
        return socket;
    }

    public Transport connect(String host, int port, int timeout) throws IOException {
        try {
            return connect(getSession(false), host, port, timeout);
        } catch (JSchException ignore) {
            try {
                return connect(getSession(true), host, port, timeout);
            } catch (JSchException e) {
                throw new IOException(e);
            }
        }
    }

    static class ChannelTransport implements Transport {
        private Channel channel;
        private String host;
        private int port;
        private SSHTransportProvider provider;
        public ChannelTransport(Channel channel, SSHTransportProvider provider, String host, int port) throws IOException {
            this.channel = channel;
            this.host = host;
            this.port = port;
            this.provider = provider;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return channel.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return channel.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            provider.closeSocket(this);
        }

        @Override
        public String toString() {
            return "StreamForwarder[host=" + host +
                    ",port=" + port + "]";
        }
    }
}
