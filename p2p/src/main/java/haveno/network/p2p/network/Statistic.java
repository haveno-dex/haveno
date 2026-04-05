/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.network;

import haveno.common.UserThread;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.util.Tuple2;
import haveno.common.util.Utilities;
import haveno.network.utils.EventThrottler;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Network statistics per connection. As we are also interested in total network statistics
 * we use static properties to get traffic of all connections combined.
 */
@Slf4j
public class Statistic {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Static
    ///////////////////////////////////////////////////////////////////////////////////////////


    private final static long startTime = System.currentTimeMillis();
    private final static AtomicLong totalSentBytes = new AtomicLong(0);
    private final static LongProperty totalSentBytesProperty = new SimpleLongProperty(0);
    private final static DoubleProperty totalSentBytesPerSecProperty = new SimpleDoubleProperty(0);
    private final static AtomicLong totalReceivedBytes = new AtomicLong(0);
    private final static LongProperty totalReceivedBytesProperty = new SimpleLongProperty(0);
    private final static DoubleProperty totalReceivedBytesPerSecProperty = new SimpleDoubleProperty(0);
    private final static Map<String, Integer> totalReceivedMessages = new ConcurrentHashMap<>();
    private final static Map<String, Integer> totalSentMessages = new ConcurrentHashMap<>();
    private final static LongProperty numTotalSentMessages = new SimpleLongProperty(0);
    private final static DoubleProperty numTotalSentMessagesPerSec = new SimpleDoubleProperty(0);
    private final static LongProperty numTotalReceivedMessages = new SimpleLongProperty(0);
    private final static DoubleProperty numTotalReceivedMessagesPerSec = new SimpleDoubleProperty(0);
    private final static EventThrottler statisticThrottler = new EventThrottler(1000, TimeUnit.MILLISECONDS);

    static {
        UserThread.runPeriodically(() -> {
            numTotalSentMessages.set(totalSentMessages.values().stream().mapToInt(Integer::intValue).sum());
            numTotalReceivedMessages.set(totalReceivedMessages.values().stream().mapToInt(Integer::intValue).sum());

            long passed = (System.currentTimeMillis() - startTime) / 1000;
            numTotalSentMessagesPerSec.set(((double) numTotalSentMessages.get()) / passed);
            numTotalReceivedMessagesPerSec.set(((double) numTotalReceivedMessages.get()) / passed);

            totalSentBytesPerSecProperty.set(((double) totalSentBytesProperty.get()) / passed);
            totalReceivedBytesPerSecProperty.set(((double) totalReceivedBytesProperty.get()) / passed);
        }, 1);

        // We log statistics every 60 minutes
        UserThread.runPeriodically(() -> {
            String ls = System.lineSeparator();
            log.info("Accumulated network statistics:" + ls +
                            "Bytes sent: {};" + ls +
                            "Number of sent messages/Sent messages: {} / {};" + ls +
                            "Number of sent messages per sec: {};" + ls +
                            "Bytes received: {}" + ls +
                            "Number of received messages/Received messages: {} / {};" + ls +
                            "Number of received messages per sec: {}" + ls,
                    Utilities.readableFileSize(totalSentBytesProperty.get()),
                    numTotalSentMessages.get(), totalSentMessages,
                    numTotalSentMessagesPerSec.get(),
                    Utilities.readableFileSize(totalReceivedBytesProperty.get()),
                    numTotalReceivedMessages.get(), totalReceivedMessages,
                    numTotalReceivedMessagesPerSec.get());
        }, TimeUnit.MINUTES.toSeconds(60));
    }

    public static LongProperty totalSentBytesProperty() {
        return totalSentBytesProperty;
    }

    public static DoubleProperty totalSentBytesPerSecProperty() {
        return totalSentBytesPerSecProperty;
    }

    public static LongProperty totalReceivedBytesProperty() {
        return totalReceivedBytesProperty;
    }

    public static DoubleProperty totalReceivedBytesPerSecProperty() {
        return totalReceivedBytesPerSecProperty;
    }

    public static LongProperty numTotalSentMessagesProperty() {
        return numTotalSentMessages;
    }

    public static DoubleProperty numTotalSentMessagesPerSecProperty() {
        return numTotalSentMessagesPerSec;
    }

    public static LongProperty numTotalReceivedMessagesProperty() {
        return numTotalReceivedMessages;
    }

    public static DoubleProperty numTotalReceivedMessagesPerSecProperty() {
        return numTotalReceivedMessagesPerSec;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Instance fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final Date creationDate;
    private long lastActivityTimestamp = System.currentTimeMillis();
    private final AtomicLong sentBytes = new AtomicLong(0);
    private final LongProperty sentBytesProperty = new SimpleLongProperty(0);
    private final AtomicLong receivedBytes = new AtomicLong(0);
    private final LongProperty receivedBytesProperty = new SimpleLongProperty(0);
    private final Map<String, Integer> receivedMessages = new ConcurrentHashMap<>();
    private final Map<String, Integer> sentMessages = new ConcurrentHashMap<>();
    private final IntegerProperty roundTripTime = new SimpleIntegerProperty(0);


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    Statistic() {
        creationDate = new Date();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Update, increment
    ///////////////////////////////////////////////////////////////////////////////////////////

    void updateLastActivityTimestamp() {
        Tuple2<Boolean, Long> throttleResult = statisticThrottler.onEvent();
        if (!throttleResult.first) {
            UserThread.execute(() -> lastActivityTimestamp = System.currentTimeMillis());
        } else {
            lastActivityTimestamp = System.currentTimeMillis();
        }
    }

    void addSentBytes(int value) {
        sentBytes.addAndGet(value);
        totalSentBytes.addAndGet(value);
        Tuple2<Boolean, Long> throttleResult = statisticThrottler.onEvent();
        if (!throttleResult.first) {
            UserThread.execute(() -> {
                sentBytesProperty.set(sentBytes.get());
                totalSentBytesProperty.set(totalSentBytes.get());
            });
        }
    }

    void addReceivedBytes(int value) {
        receivedBytes.addAndGet(value);
        totalReceivedBytes.addAndGet(value);
        Tuple2<Boolean, Long> throttleResult = statisticThrottler.onEvent();
        if (!throttleResult.first) {
            UserThread.execute(() -> {
                receivedBytesProperty.set(receivedBytes.get());
                totalReceivedBytesProperty.set(totalReceivedBytes.get());
            });
        }
    }

    // TODO would need msg inspection to get useful information...
    void addReceivedMessage(NetworkEnvelope networkEnvelope) {
        String messageClassName = networkEnvelope.getClass().getSimpleName();
        int counter = 1;
        if (receivedMessages.containsKey(messageClassName)) {
            counter = receivedMessages.get(messageClassName) + 1;
        }
        receivedMessages.put(messageClassName, counter);

        counter = 1;
        if (totalReceivedMessages.containsKey(messageClassName)) {
            counter = totalReceivedMessages.get(messageClassName) + 1;
        }
        totalReceivedMessages.put(messageClassName, counter);
    }

    void addSentMessage(NetworkEnvelope networkEnvelope) {
        String messageClassName = networkEnvelope.getClass().getSimpleName();
        int counter = 1;
        if (sentMessages.containsKey(messageClassName)) {
            counter = sentMessages.get(messageClassName) + 1;
        }
        sentMessages.put(messageClassName, counter);

        counter = 1;
        if (totalSentMessages.containsKey(messageClassName)) {
            counter = totalSentMessages.get(messageClassName) + 1;
        }
        totalSentMessages.put(messageClassName, counter);
    }

    public void setRoundTripTime(int roundTripTime) {
        this.roundTripTime.set(roundTripTime);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public long getLastActivityTimestamp() {
        return lastActivityTimestamp;
    }

    public long getLastActivityAge() {
        return System.currentTimeMillis() - lastActivityTimestamp;
    }

    public long getSentBytesProperty() {
        return sentBytesProperty.get();
    }

    public LongProperty sentBytesProperty() {
        return sentBytesProperty;
    }

    public long getReceivedBytesProperty() {
        return receivedBytesProperty.get();
    }

    public LongProperty receivedBytesProperty() {
        return receivedBytesProperty;
    }

    public Date getCreationDate() {
        return creationDate;
    }

    public IntegerProperty roundTripTimeProperty() {
        return roundTripTime;
    }

    public static long getTotalSentBytes() {
        return totalSentBytesProperty.get();
    }

    public static double getTotalSentBytesPerSec() {
        return totalSentBytesPerSecProperty.get();
    }

    public static long getTotalReceivedBytes() {
        return totalReceivedBytesProperty.get();
    }

    public static double getTotalReceivedBytesPerSec() {
        return totalReceivedBytesPerSecProperty.get();
    }

    public static double numTotalReceivedMessagesPerSec() {
        return numTotalReceivedMessagesPerSec.get();
    }

    public static double getNumTotalSentMessagesPerSec() {
        return numTotalSentMessagesPerSec.get();
    }

    @Override
    public String toString() {
        return "Statistic{" +
                "\n     creationDate=" + creationDate +
                ",\n     lastActivityTimestamp=" + lastActivityTimestamp +
                ",\n     sentBytes=" + sentBytesProperty +
                ",\n     receivedBytes=" + receivedBytesProperty +
                ",\n     receivedMessages=" + receivedMessages +
                ",\n     sentMessages=" + sentMessages +
                ",\n     roundTripTime=" + roundTripTime +
                "\n}";
    }
}
