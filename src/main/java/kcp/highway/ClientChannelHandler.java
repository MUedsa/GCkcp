package kcp.highway;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;

/**
 * Created by JinMiao
 * 2019-06-26.
 */
public class ClientChannelHandler extends ChannelInboundHandlerAdapter {
    static final Logger logger = LoggerFactory.getLogger(ClientChannelHandler.class);

    private final IChannelManager channelManager;

    private final HandshakeManager handshakeManager;

    public ClientChannelHandler(IChannelManager channelManager, HandshakeManager handshakeManager) {
        this.channelManager = channelManager;
        this.handshakeManager = handshakeManager;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Something went wrong due to ", cause);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object object) {
        DatagramPacket msg = (DatagramPacket) object;
        Ukcp ukcp = this.channelManager.get(msg);
        ByteBuf content = msg.content();
        if (ukcp != null) {
            ukcp.read(content);
        } else if(content.readableBytes() == 20){
            handleEnet(content, ctx.channel().remoteAddress());
        }
    }


    public void handleEnet(ByteBuf data, SocketAddress socketAddress) {
        if (data == null || data.readableBytes() != 20) {
            return;
        }
        // Get
        int code = data.readInt();
        data.readUnsignedInt(); // Empty
        data.readUnsignedInt(); // Empty
        int enet = data.readInt();
        data.readUnsignedInt();

            if (code == 325) {// Handshake
                try {
                    handshakeManager.success(socketAddress);
                } catch (Throwable e) {
                    logger.error("handshakeManager success error", e);
                }
            }

    }
}
