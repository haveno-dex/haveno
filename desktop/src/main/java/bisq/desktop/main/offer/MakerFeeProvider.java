package bisq.desktop.main.offer;

import bisq.core.offer.OfferUtil;
import bisq.core.user.Preferences;

import org.bitcoinj.core.Coin;

public class MakerFeeProvider {
    public Coin getMakerFee(Preferences preferences, Coin amount) {
        return OfferUtil.getMakerFee(preferences, amount);
    }
}
