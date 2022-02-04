package bisq.core.btc.setup;

import bisq.common.UserThread;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

import java.util.Date;

public class DownloadListener {
    private final DoubleProperty percentage = new SimpleDoubleProperty(-1);

    public void progress(double percentage, int blocksLeft, Date date) {
        UserThread.execute(() -> this.percentage.set(percentage / 100d));
    }

    public void doneDownload() {
        UserThread.execute(() -> this.percentage.set(1d));
    }

    public ReadOnlyDoubleProperty percentageProperty() {
        return percentage;
    }
}
