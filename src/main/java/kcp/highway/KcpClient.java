package kcp.highway;

import kcp.highway.erasure.fec.Fec;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.HashedWheelTimer;
import kcp.highway.threadPool.IMessageExecutor;
import kcp.highway.threadPool.IMessageExecutorPool;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * kcp客户端
 * Created by JinMiao
 * 2019-06-26.
 */
public class KcpClient {


    private IMessageExecutorPool iMessageExecutorPool;
    private Bootstrap bootstrap;
    private EventLoopGroup nioEventLoopGroup;
    /**
     * 客户端的连接集合
     **/
    private IChannelManager channelManager;
    private HashedWheelTimer hashedWheelTimer;
    private HandshakeManager handshakeManager;

    /**
     * 定时器线程工厂
     **/
    private static class TimerThreadFactory implements ThreadFactory {
        private final AtomicInteger timeThreadName = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "KcpClientTimerThread " + timeThreadName.addAndGet(1));
        }
    }

    public void init(ChannelConfig channelConfig) {
        if (channelConfig.isUseConvChannel()) {
            int convIndex = 0;
            if (channelConfig.getFecAdapt() != null) {
                convIndex += Fec.fecHeaderSizePlus2;
            }
            channelManager = new ClientConvChannelManager(convIndex);
        } else {
            channelManager = new ClientAddressChannelManager();
        }
        this.iMessageExecutorPool = channelConfig.getiMessageExecutorPool();
        nioEventLoopGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * channelConfig.getMultiply());

        hashedWheelTimer = new HashedWheelTimer(new TimerThreadFactory(), channelConfig.getUseWheelTimer(), TimeUnit.MILLISECONDS);

        handshakeManager = new HandshakeManager(hashedWheelTimer);

        bootstrap = new Bootstrap();
        bootstrap.channel(NioDatagramChannel.class);
        bootstrap.group(nioEventLoopGroup);
        bootstrap.handler(new ChannelInitializer<NioDatagramChannel>() {
            @Override
            protected void initChannel(NioDatagramChannel ch) {
                ChannelPipeline cp = ch.pipeline();
                if (channelConfig.isCrc32Check()) {
                    Crc32Encode crc32Encode = new Crc32Encode();
                    Crc32Decode crc32Decode = new Crc32Decode();
                    cp.addLast(crc32Encode);
                    cp.addLast(crc32Decode);
                }
                cp.addLast(new ClientChannelHandler(channelManager, handshakeManager));
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    /**
     * 重连接口
     * 使用旧的kcp对象，出口ip和端口替换为新的
     * 在4G切换为wifi等场景使用
     *
     * @param ukcp
     */
    public void reconnect(Ukcp ukcp) {
        if (!(channelManager instanceof ClientConvChannelManager)) {
            throw new UnsupportedOperationException("reconnect can only be used in convChannel");
        }
        ukcp.getiMessageExecutor().execute(() -> {
            User user = ukcp.user();
            user.getChannel().close();
            InetSocketAddress localAddress = new InetSocketAddress(0);
            ChannelFuture channelFuture = bootstrap.connect(user.getRemoteAddress(), localAddress);
            user.setChannel(channelFuture.channel());
        });
    }

    public Ukcp connect(InetSocketAddress localAddress, InetSocketAddress remoteAddress, ChannelConfig channelConfig, KcpListener kcpListener) {
        if (localAddress == null) {
            localAddress = new InetSocketAddress(0);
        }
        ChannelFuture channelFuture = bootstrap.connect(remoteAddress, localAddress);

        //= bootstrap.bind(localAddress);
        ChannelFuture sync = channelFuture.syncUninterruptibly();
        NioDatagramChannel channel = (NioDatagramChannel) sync.channel();
        localAddress = channel.localAddress();

        User user = new User(channel, remoteAddress, localAddress);
        IMessageExecutor iMessageExecutor = iMessageExecutorPool.getIMessageExecutor();
        KcpOutput kcpOutput = new KcpOutPutImp();

        Ukcp ukcp = new Ukcp(kcpOutput, kcpListener, iMessageExecutor, channelConfig, channelManager);
        ukcp.user(user);

        InetSocketAddress finalLocalAddress = localAddress;
        handshakeManager.newHandshake(user, () -> {
            int enet = 1; // what enet?
            Ukcp.sendHandshakeRep(user, enet, ukcp.getConv());
            return null;
        }, success -> {
            if(success) {
                channelManager.newConnect(finalLocalAddress, ukcp, null);
                iMessageExecutor.execute(() -> {
                    try {
                        ukcp.getKcpListener().onConnected(ukcp);
                    } catch (Throwable throwable) {
                        ukcp.getKcpListener().handleException(throwable, ukcp);
                    }
                });
            }else{
                iMessageExecutor.execute(() -> {
                    ukcp.close(false);
                    try {
                        ukcp.getKcpListener().handleClose(ukcp);
                    } catch (Throwable throwable) {
                        ukcp.getKcpListener().handleException(throwable, ukcp);
                    }
                });
            }
        });
        ScheduleTask scheduleTask = new ScheduleTask(iMessageExecutor, ukcp, hashedWheelTimer);
        hashedWheelTimer.newTimeout(scheduleTask, ukcp.getInterval(), TimeUnit.MILLISECONDS);
        return ukcp;
    }

    public Ukcp connect(InetSocketAddress remoteAddress, ChannelConfig channelConfig, KcpListener kcpListener) {
        return connect(null, remoteAddress, channelConfig, kcpListener);
    }

    public void stop() {
        channelManager.getAll().forEach(uKcp -> {
            try {
                uKcp.close();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        });
        if(handshakeManager != null){
            handshakeManager.release();
        }
        if (iMessageExecutorPool != null) {
            iMessageExecutorPool.stop();
        }
        if (nioEventLoopGroup != null) {
            nioEventLoopGroup.shutdownGracefully();
        }
        if (hashedWheelTimer != null) {
            hashedWheelTimer.stop();
        }
    }

    public IChannelManager getChannelManager() {
        return channelManager;
    }
}
