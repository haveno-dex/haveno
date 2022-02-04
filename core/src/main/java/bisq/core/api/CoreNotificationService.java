package bisq.core.api;

import bisq.core.api.CoreApi.NotificationListener;
import bisq.core.api.model.TradeInfo;
import bisq.core.trade.Trade;
import bisq.proto.grpc.NotificationMessage;
import bisq.proto.grpc.NotificationMessage.NotificationType;
import javax.inject.Singleton;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class CoreNotificationService {

    private final Object lock = new Object();
    private final List<NotificationListener> listeners = new LinkedList<>();

    public void addListener(@NonNull NotificationListener listener) {
        synchronized (lock) {
            listeners.add(listener);
        }
    }

    public void sendNotification(@NonNull NotificationMessage notification) {
        synchronized (lock) {
            for (Iterator<NotificationListener> iter = listeners.iterator(); iter.hasNext(); ) {
                NotificationListener listener = iter.next();
                try {
                    listener.onMessage(notification);
                } catch (RuntimeException e) {
                    log.warn("Failed to send notification to listener {}: {}", listener, e.getMessage());
                    iter.remove();
                }
            }
        }
    }

    public void sendAppInitializedNotification() {
        sendNotification(NotificationMessage.newBuilder()
                .setType(NotificationType.APP_INITIALIZED)
                .setTimestamp(System.currentTimeMillis())
                .build());
    }

    public void sendTradeNotification(Trade trade, String title, String message) {
        sendNotification(NotificationMessage.newBuilder()
                .setType(NotificationType.TRADE_UPDATE)
                .setTrade(TradeInfo.toTradeInfo(trade).toProtoMessage())
                .setTimestamp(System.currentTimeMillis())
                .setTitle(title)
                .setMessage(message).build());
    }
}
