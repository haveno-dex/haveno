package bisq.core.trade.protocol;

import bisq.core.trade.messages.TradeMessage;
import bisq.network.p2p.NodeAddress;

/**
 * Receives notifications of decrypted, verified trade messages.
 */
public class TradeMessageListener {
  public void onVerifiedTradeMessage(TradeMessage message, NodeAddress sender) { }
}
