package me.proxy;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class Socks5Service implements Server.Service {
    static Logger logger = LoggerFactory.getLogger(Socks5Service.class);
    static AtomicInteger atomicIndex = new AtomicInteger(0);

    public static final byte CMD_CONNECT = 0x01;
    public static final byte CMD_BIND = 0x02;
    public static final byte CMD_UDP = 0x03;

    public static final byte ATYP_IPV4 = 0x01;
    public static final byte ATYP_DOMAIN = 0x03;
    public static final byte ATYP_IPV6 = 0x04;

    private boolean forceProxy;
    public Socks5Service(boolean forceProxy) {
        this.forceProxy = forceProxy;
    }

    private void handleService(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte version = in.readByte();
        if (version != 0x05) {
            throw new RuntimeException("version " + version + " is not supported");
        }

        byte cmd = in.readByte();
        if (cmd != CMD_CONNECT) {
            throw new RuntimeException("command " + cmd + " is not supported");
        }

        byte reserved = in.readByte();
        if (reserved != 0) {
            throw new RuntimeException("unknown reserved flag");
        }

        String host;
        byte addressType = in.readByte();
        switch (addressType) {
            case ATYP_IPV4: {
                byte[] addressData = new byte[4];
                in.readFully(addressData);
                InetAddress inetAddress = InetAddress.getByAddress(addressData);
                host = inetAddress.getHostAddress();
                break;
            }
            case ATYP_DOMAIN: {
                int len = in.readUnsignedByte();
                byte[] domainData = new byte[len];
                in.readFully(domainData);
                host = new String(domainData, StandardCharsets.US_ASCII);
                break;
            }
            case ATYP_IPV6: {
                byte[] addressData = new byte[16];
                in.readFully(addressData);
                InetAddress inetAddress = InetAddress.getByAddress(addressData);
                host = inetAddress.getHostAddress();
                break;
            }
            default:
                throw new RuntimeException("only ipv4 and domain supported");
        }

        int port = in.readUnsignedShort();

        //CONNECT REPLY
        OutputStream out = socket.getOutputStream();
        out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, -1, -1});
        out.flush();

        MDC.put("request", atomicIndex.getAndIncrement() + "-" + host + ":" + port);
        logger.debug("Process socks5 connect request to {} {}", host, port);
        try {
            ProxyService.proxy(socket, host, port, !forceProxy);
        } finally {
            MDC.remove("request");
        }
    }

    private void handshake(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte version = in.readByte();
        if (version != 0x05) {
            throw new RuntimeException("version " + version + "is not supported");
        }
        int mCount = in.readUnsignedByte();
        byte[] methods = new byte[mCount];
        in.readFully(methods);

        for(byte method: methods) {
            if (method == 0) {
                OutputStream out = socket.getOutputStream();
                out.write(new byte[]{0x05, 0x00});
                out.flush();
                return;
            }
        }

        throw new RuntimeException("method NO AUTHENTICATION REQUIRED is not found");
    }

    @Override
    public void serve(Socket socket) throws Exception {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15 * 1000);
        } catch (SocketException e) {
            e.printStackTrace();
        }

        handshake(socket);
        handleService(socket);
    }
}
