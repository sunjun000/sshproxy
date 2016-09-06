package me.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Server {
    static Logger logger = LoggerFactory.getLogger(Server.class);

    public interface Service {
        void run() throws Exception;
    }

    public interface ServiceFactory {
        Service create(Socket socket);
    }


    private ExecutorService executor;
    private ServiceFactory serviceFactory;
    private ServerSocket serverSocket;
    private Thread thread;
    private boolean running = false;
    private Lock lock = new ReentrantLock();
    public Server(ServerSocket serverSocket, ServiceFactory serviceFactory) {
        this.serverSocket = serverSocket;
        this.serviceFactory = serviceFactory;
    }

    public Server(int port, ServiceFactory serviceFactory) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.serverSocket.setReuseAddress(true);
        this.serviceFactory = serviceFactory;
    }

    public void start() {
        lock.lock();
        try {
            if (running) {
                return;
            }
            running = true;
        } finally {
            lock.unlock();
        }
        executor = new ThreadPoolExecutor(50, 400, 0L, TimeUnit.MILLISECONDS,
                                            new LinkedBlockingQueue<Runnable>(),
                                            new DaemonThreadFactory("server"));
        thread = new Thread(new Runnable() {
            public void run() {
                while(running) {
                    try {
                        final Socket socket = serverSocket.accept();
                        executor.submit(new Runnable() {
                            public void run() {
                                try {
                                    Service service = serviceFactory.create(socket);
                                    service.run();
                                } catch (Exception e) {
                                    logger.error("work thread error", e);
                                } finally {
                                    try {
                                        socket.close();
                                    } catch (IOException ignore) {
                                    }
                                }
                            }
                        });
                    } catch (Exception e) {
                        logger.error("main thread error", e);
                    }
                }
            }
        });
        if(Thread.currentThread().getId() != 1) {
            thread.setDaemon(true);
        }
        thread.start();
    }

    public void stop() throws IOException {
        lock.lock();
        try {
            if (!running) {
                return;
            }
            running = false;
        } finally {
            lock.unlock();
        }

        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
        executor.shutdownNow();
        serverSocket.close();
        try {
            thread.join(60 * 1000);
        } catch (InterruptedException ignore) {
        }
    }


    static class DaemonThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        public DaemonThreadFactory(String poolName) {
            namePrefix = poolName + "-" + poolNumber.getAndIncrement() + "-thread-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r,namePrefix + threadNumber.getAndIncrement());
            if (!t.isDaemon())
                t.setDaemon(true);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }
}
