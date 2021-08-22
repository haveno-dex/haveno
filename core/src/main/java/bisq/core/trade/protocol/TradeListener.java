package bisq.core.trade.protocol;

import bisq.core.trade.messages.TradeMessage;
import bisq.network.p2p.AckMessage;
import bisq.network.p2p.NodeAddress;

/**
 * Receives notifications of decrypted, verified trade and ack messages.
 */
public class TradeListener {
  public void onVerifiedTradeMessage(TradeMessage message, NodeAddress sender) { }
  public void onAckMessage(AckMessage ackMessage, NodeAddress sender) { }
}
