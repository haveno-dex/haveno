package haveno.asset.tokens;

import haveno.asset.Trc20Token;

public class TetherUSDTRC20 extends Trc20Token {
    public TetherUSDTRC20() {
        // If you add a new USDT variant or want to change this ticker symbol you should also look here:
        // core/src/main/java/haveno/core/provider/price/PriceProvider.java:getAll()
        super("Tether USD (TRC20)", "USDT-TRC20");
    }
}
