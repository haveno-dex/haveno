package bisq.core.api;

import bisq.core.api.CoreApi.NotificationListener;

import bisq.proto.grpc.NotificationMessage;

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
                    log.warn("Failed to send message {} to listener {}", notification, listener, e);
                    iter.remove();
                }
            }
        }
    }
}
