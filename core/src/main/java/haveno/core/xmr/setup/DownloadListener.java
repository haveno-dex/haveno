package haveno.core.xmr.setup;

import haveno.common.UserThread;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;

import java.util.Date;

public class DownloadListener {
    private final DoubleProperty percentage = new SimpleDoubleProperty(-1);

    public void progress(double percentage, long blocksLeft, Date date) {
        UserThread.await(() -> this.percentage.set(percentage));
    }

    public void doneDownload() {
        UserThread.await(() -> this.percentage.set(1d));
    }

    public ReadOnlyDoubleProperty percentageProperty() {
        return percentage;
    }
}
