package kcp.highway;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class HandshakeManager {
    static final Logger logger = LoggerFactory.getLogger(HandshakeManager.class);

    public static final long HANDSHAKE_RETRY_DELAY = 300;

    public static final TimeUnit HANDSHAKE_RETRY_DELAY_UNIT = TimeUnit.MILLISECONDS;


    private final Map<SocketAddress, HandshakeWaiter> waiterMap = new ConcurrentHashMap<>();

    private HashedWheelTimer hashedWheelTimer;

    public HandshakeManager(HashedWheelTimer hashedWheelTimer){
        this.hashedWheelTimer = hashedWheelTimer;
    }

    public void newHandshake(User user, Callable<Void> handshakeCall, Consumer<Boolean> finalCallback){
        HandshakeWaiter waiter = new HandshakeWaiter(user.getRemoteAddress(), handshakeCall, finalCallback);
        waiterMap.put(user.getRemoteAddress(), waiter);
        callHandshake(handshakeCall, finalCallback);
        hashedWheelTimer.newTimeout(new WaitHandshakeRespTask(user.getRemoteAddress(), this), HANDSHAKE_RETRY_DELAY, HANDSHAKE_RETRY_DELAY_UNIT);
    }

    public void success(SocketAddress socketAddress){
        HandshakeWaiter waiter = findWaiter(socketAddress);
        if(waiter != null){
            waiterMap.remove(socketAddress);
            callFinalCallBack(waiter.finalCallback, Boolean.TRUE);
        }
    }

    public HandshakeWaiter findWaiter(SocketAddress socketAddress){
        return waiterMap.get(socketAddress);
    }

    public void timeout(SocketAddress socketAddress){
        HandshakeWaiter waiter = findWaiter(socketAddress);
        if(waiter != null){
            waiterMap.remove(socketAddress);
            callFinalCallBack(waiter.finalCallback, Boolean.FALSE);
        }
    }

    public void release(){
        waiterMap.clear();
        hashedWheelTimer = null;
    }

    static class WaitHandshakeRespTask implements TimerTask {

        private final SocketAddress socketAddress;
        private final HandshakeManager handshakeManager;
        private int retryTimes = 0;

        public WaitHandshakeRespTask(SocketAddress socketAddress, HandshakeManager handshakeManager) {
            this.socketAddress = socketAddress;
            this.handshakeManager = handshakeManager;
        }

        @Override
        public void run(Timeout timeout) throws Exception {
            HandshakeWaiter waiter = handshakeManager.findWaiter(socketAddress);
            if(waiter != null){
                if(retryTimes++ < 10){
                    callHandshake(waiter.handshakeCall, waiter.finalCallback);
                    rePut(timeout);
                }else{
                    handshakeManager.timeout(socketAddress);
                }
            }
        }

        private void rePut(Timeout timeout) {
            if(timeout != null && !timeout.isCancelled()){
                Timer timer = timeout.timer();
                timer.newTimeout(timeout.task(), HANDSHAKE_RETRY_DELAY, HANDSHAKE_RETRY_DELAY_UNIT);
            }
        }
    }

    public static void callFinalCallBack(Consumer<Boolean> finalCallback, Boolean success){
        if(finalCallback != null){
            try{
                finalCallback.accept(success);
            }catch (Exception e){
                logger.error("callFinalCallBack error", e);
            }
        }
    }

    public static void callHandshake(Callable<Void> handshakeCall, Consumer<Boolean> finalCallback){
        if(handshakeCall != null){
            try{
                logger.info("callHandshake");
                handshakeCall.call();
            }catch (Exception e){
                logger.error("callHandshake error", e);
                callFinalCallBack(finalCallback, Boolean.FALSE);
            }
        }
    }

    record HandshakeWaiter(SocketAddress socketAddress, Callable<Void> handshakeCall, Consumer<Boolean> finalCallback) {
    }
}
