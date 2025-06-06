package com.linkedin.davinci.blobtransfer.server;

import com.linkedin.davinci.blobtransfer.BlobSnapshotManager;
import com.linkedin.davinci.blobtransfer.BlobTransferAclHandler;
import com.linkedin.venice.security.SSLFactory;
import com.linkedin.venice.service.AbstractVeniceService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.traffic.GlobalChannelTrafficShapingHandler;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class P2PBlobTransferService extends AbstractVeniceService {
  private static final Logger LOGGER = LogManager.getLogger(P2PBlobTransferService.class);

  private final ServerBootstrap serverBootstrap;
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private final int port;
  private ChannelFuture channelFuture;
  private BlobSnapshotManager blobSnapshotManager;
  // TODO 1: Quota support
  // TODO 2: consider adding support for HTTP2
  // TODO 3: add compression support
  // TODO 4: consider either increasing worker threads or have a dedicated thread pool to handle requests.

  public P2PBlobTransferService(
      int port,
      String baseDir,
      int blobTransferMaxTimeoutInMin,
      BlobSnapshotManager blobSnapshotManager,
      GlobalChannelTrafficShapingHandler globalChannelTrafficShapingHandler,
      Optional<SSLFactory> sslFactory,
      Optional<BlobTransferAclHandler> aclHandler,
      int maxAllowedConcurrentSnapshotUsers) {
    this.port = port;
    this.serverBootstrap = new ServerBootstrap();
    this.blobSnapshotManager = blobSnapshotManager;

    Class<? extends ServerChannel> socketChannelClass = NioServerSocketChannel.class;

    if (Epoll.isAvailable()) {
      bossGroup = new EpollEventLoopGroup(1);
      workerGroup = new EpollEventLoopGroup(6);
      socketChannelClass = EpollServerSocketChannel.class;
    } else {
      bossGroup = new NioEventLoopGroup(1);
      workerGroup = new NioEventLoopGroup(6);
    }

    serverBootstrap.group(bossGroup, workerGroup)
        .channel(socketChannelClass)
        .childHandler(
            new BlobTransferNettyChannelInitializer(
                baseDir,
                blobTransferMaxTimeoutInMin,
                blobSnapshotManager,
                globalChannelTrafficShapingHandler,
                sslFactory,
                aclHandler,
                maxAllowedConcurrentSnapshotUsers))
        .option(ChannelOption.SO_BACKLOG, 1000)
        .option(ChannelOption.SO_REUSEADDR, true)
        .childOption(ChannelOption.SO_KEEPALIVE, true)
        .childOption(ChannelOption.TCP_NODELAY, true);
  }

  @Override
  public boolean startInner() throws Exception {
    LOGGER.info("Starting up NettyP2PBlobTransferManager");
    channelFuture = serverBootstrap.bind(port).sync();
    LOGGER.info("NettyP2PBlobTransferManager started on port: {}", port);
    return true;
  }

  @Override
  public void stopInner() throws Exception {
    LOGGER.info("Shutting down NettyP2PBlobTransferManager");
    if (blobSnapshotManager != null) {
      blobSnapshotManager.shutdown();
    }
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
    channelFuture.channel().closeFuture().sync();
  }
}
