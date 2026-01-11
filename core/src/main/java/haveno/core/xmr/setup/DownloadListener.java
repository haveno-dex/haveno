package haveno.core.xmr.setup;

import haveno.common.UserThread;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;

public class DownloadListener {
    private final DoubleProperty percentage = new SimpleDoubleProperty(-1);
    private final LongProperty blocksRemaining = new SimpleLongProperty(-1);
    private final LongProperty numUpdates = new SimpleLongProperty(0);

    public void progress(double percentage, long blocksRemaining) {
        if (!UserThread.isUserThread(Thread.currentThread())) {
            throw new IllegalStateException("DownloadListener.progress() must be called on the JavaFX Application Thread");
        }
        this.percentage.set(percentage);
        this.blocksRemaining.set(blocksRemaining);
        this.numUpdates.set(this.numUpdates.get() + 1);
    }

    public void doneDownload() {
        if (!UserThread.isUserThread(Thread.currentThread())) {
            throw new IllegalStateException("DownloadListener.doneDownload() must be called on the JavaFX Application Thread");
        }
        this.percentage.set(1d);
    }

    public LongProperty numUpdates() {
        return numUpdates;
    }

    public ReadOnlyDoubleProperty percentageProperty() {
        return percentage;
    }

    public LongProperty blocksRemainingProperty() {
        return blocksRemaining;
    }
}
