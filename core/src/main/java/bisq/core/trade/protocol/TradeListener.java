package haveno.core.trade.protocol;

import haveno.core.trade.messages.TradeMessage;
import haveno.network.p2p.AckMessage;
import haveno.network.p2p.NodeAddress;

/**
 * Receives notifications of decrypted, verified trade and ack messages.
 */
public class TradeListener {
  public void onVerifiedTradeMessage(TradeMessage message, NodeAddress sender) { }
  public void onAckMessage(AckMessage ackMessage, NodeAddress sender) { }
}
