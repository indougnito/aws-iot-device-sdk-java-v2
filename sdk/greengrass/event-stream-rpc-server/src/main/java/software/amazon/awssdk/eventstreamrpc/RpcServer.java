package software.amazon.awssdk.eventstreamrpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.eventstream.ServerConnection;
import software.amazon.awssdk.crt.eventstream.ServerConnectionHandler;
import software.amazon.awssdk.crt.eventstream.ServerListener;
import software.amazon.awssdk.crt.eventstream.ServerListenerHandler;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.ServerBootstrap;
import software.amazon.awssdk.crt.io.ServerTlsContext;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContextOptions;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The RPCServer implementation
 */
public class RpcServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcServer.class);

    private final EventLoopGroup eventLoopGroup;
    private final SocketOptions socketOptions;
    private final TlsContextOptions tlsContextOptions;
    private final String hostname;
    private final int port;
    private final EventStreamRPCServiceHandler eventStreamRPCServiceHandler;

    private ServerBootstrap serverBootstrap;
    private ServerTlsContext tlsContext;
    private ServerListener listener;
    private AtomicBoolean serverRunning;
    private int boundPort = -1;

    /**
     * Creates a new RPC Server
     * @param eventLoopGroup The EventLoopGroup to use in the RPC server
     * @param socketOptions The SocketOptions to use in the RPC server
     * @param tlsContextOptions The TlsContextOptions to use in the RPC server
     * @param hostname The hostname to use in the RPC server
     * @param port The port to use in the RPC server
     * @param serviceHandler The ServceHandler to use in the RPC server
     */
    public RpcServer(EventLoopGroup eventLoopGroup, SocketOptions socketOptions, TlsContextOptions tlsContextOptions, String hostname, int port, EventStreamRPCServiceHandler serviceHandler) {
        this.eventLoopGroup = eventLoopGroup;
        this.socketOptions = socketOptions;
        this.tlsContextOptions = tlsContextOptions;
        this.hostname = hostname;
        this.port = port;
        this.eventStreamRPCServiceHandler = serviceHandler;
        this.serverRunning = new AtomicBoolean(false);
    }

    /**
     * Runs the server in the constructor supplied event loop group
     */
    public void runServer() {
        validateServiceHandler();
        if (!serverRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("Failed to start IpcServer. It's already started or has not completed a prior shutdown!");
        }
        serverBootstrap = new ServerBootstrap(eventLoopGroup);
        tlsContext = tlsContextOptions != null ? new ServerTlsContext(tlsContextOptions) : null;
        listener = new ServerListener(hostname, (short) port, socketOptions, tlsContext, serverBootstrap, new ServerListenerHandler() {
                @Override
                public ServerConnectionHandler onNewConnection(ServerConnection serverConnection, int errorCode) {
                    try {
                        LOGGER.info("New connection code [" + CRT.awsErrorName(errorCode) + "] for " + serverConnection.getResourceLogDescription());
                        final ServiceOperationMappingContinuationHandler operationHandler =
                                new ServiceOperationMappingContinuationHandler(serverConnection, eventStreamRPCServiceHandler);
                        return operationHandler;
                    } catch (Throwable e) {
                        LOGGER.error("Throwable caught in new connection: " + e.getMessage(), e);
                        return null;
                    }
                }

                @Override
                public void onConnectionShutdown(ServerConnection serverConnection, int errorCode) {
                    LOGGER.info("Server connection closed code [" + CRT.awsErrorString(errorCode) + "]: " + serverConnection.getResourceLogDescription());
                }
            });

        boundPort = listener.getBoundPort();

        LOGGER.info("IpcServer started...");
    }

    /**
     * Get port bound to.
     *
     * @return port number that service is bound to.
     */
    public int getBoundPort() {
        return boundPort;
    }

    /**
     * Stops running server and allows the caller to wait on a CompletableFuture
     *
     * @return A future that is completed when the server stops
     */
    public CompletableFuture<Void> stopServer() {
        if (serverRunning.compareAndSet(true, false)) {
            try {
                if (listener != null) {
                    listener.close();
                    return listener.getShutdownCompleteFuture();
                }
                return CompletableFuture.completedFuture(null);
            } finally {
                listener = null;
                try {
                    if (tlsContext != null) {
                        tlsContext.close();
                    }
                } finally {
                    if(serverBootstrap != null) {
                        serverBootstrap.close();
                    }
                }
                tlsContext = null;
                serverBootstrap = null;
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Ensures a call to stop server is called when it is closed
     */
    @Override
    public void close() {
        stopServer();
    }

    /**
     * Constructor supplied EventStreamRPCServiceHandler self validates that all expected operations
     * have been wired (hand written -> dependency injected perhaps) before launching the service.
     *
     * Also verifies that auth handlers have been set
     */
    private void validateServiceHandler() {
        if (eventStreamRPCServiceHandler.getAuthenticationHandler() == null) {
            throw new InvalidServiceConfigurationException(String.format("%s authentication handler is not set!",
                    eventStreamRPCServiceHandler.getServiceName()));
        }
        if (eventStreamRPCServiceHandler.getAuthorizationHandler() == null) {
            throw new InvalidServiceConfigurationException(String.format("%s authorization handler is not set!",
                    eventStreamRPCServiceHandler.getServiceName()));
        }

        final EventStreamRPCServiceModel serviceModel = eventStreamRPCServiceHandler.getServiceModel();

        if (serviceModel == null) {
            throw new InvalidServiceConfigurationException("Handler must not have a null service model");
        }

        if (serviceModel.getServiceName() == null || serviceModel.getServiceName().isEmpty()) {
            throw new InvalidServiceConfigurationException("Service model's name is null!");
        }

        final Set<String> unsetOperations = serviceModel.getAllOperations().stream().filter(operationName -> {
            return serviceModel.getOperationModelContext(operationName) == null;
        }).collect(Collectors.toSet());
        if (!unsetOperations.isEmpty()) {
            throw new InvalidServiceConfigurationException(String.format("Service has the following unset operations {%s}",
                    unsetOperations.stream().collect(Collectors.joining(", "))));
        }

        //validates all handlers are set
        eventStreamRPCServiceHandler.validateAllOperationsSet();
    }
}
