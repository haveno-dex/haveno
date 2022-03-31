/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.trade;

import static com.google.common.base.Preconditions.checkNotNull;

import bisq.common.crypto.KeyRing;
import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.Sig;
import bisq.common.util.Tuple2;
import bisq.common.util.Utilities;
import bisq.core.btc.model.XmrAddressEntry;
import bisq.core.btc.wallet.XmrWalletService;
import bisq.core.offer.OfferPayload;
import bisq.core.offer.OfferPayload.Direction;
import bisq.core.support.dispute.mediation.mediator.Mediator;
import bisq.core.trade.messages.InitTradeRequest;
import common.utils.JsonUtils;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import monero.daemon.MoneroDaemon;
import monero.daemon.model.MoneroOutput;
import monero.daemon.model.MoneroSubmitTxResult;
import monero.daemon.model.MoneroTx;
import monero.wallet.MoneroWallet;
import monero.wallet.model.MoneroCheckTx;
import monero.wallet.model.MoneroTxConfig;
import monero.wallet.model.MoneroTxWallet;

/**
 * Collection of utilities for trading.
 * 
 * TODO (woodser): combine with TradeUtil.java ?
 */
public class TradeUtils {
    
    /**
     * Address to collect Haveno trade fees. TODO (woodser): move to config constants
     */
    public static String FEE_ADDRESS = "52FnB7ABUrKJzVQRpbMNrqDFWbcKLjFUq8Rgek7jZEuB6WE2ZggXaTf4FK6H8gQymvSrruHHrEuKhMN3qTMiBYzREKsmRKM";
    
    /**
     * Check if the arbitrator signature for an offer is valid.
     * 
     * @param arbitrator is the possible original arbitrator
     * @param signedOfferPayload is a signed offer payload
     * @return true if the arbitrator's signature is valid for the offer
     */
    public static boolean isArbitratorSignatureValid(OfferPayload signedOfferPayload, Mediator arbitrator) {
        
        // remove arbitrator signature from signed payload
        String signature = signedOfferPayload.getArbitratorSignature();
        signedOfferPayload.setArbitratorSignature(null);
        
        // get unsigned offer payload as json string
        String unsignedOfferAsJson = Utilities.objectToJson(signedOfferPayload);
        
        // verify arbitrator signature
        boolean isValid = true;
        try {
            isValid = Sig.verify(arbitrator.getPubKeyRing().getSignaturePubKey(), // TODO (woodser): assign isValid
                    unsignedOfferAsJson,
                    signature);
        } catch (Exception e) {
            isValid = false;
        }
        
        // replace signature
        signedOfferPayload.setArbitratorSignature(signature);
        
        // return result
        return isValid;
    }
    
    /**
     * Check if the maker signature for a trade request is valid.
     * 
     * @param request is the trade request to check
     * @return true if the maker's signature is valid for the trade request
     */
    public static boolean isMakerSignatureValid(InitTradeRequest request, String signature, PubKeyRing makerPubKeyRing) {
        
        // re-create trade request with signed fields
        InitTradeRequest signedRequest = new InitTradeRequest(
                request.getTradeId(),
                request.getSenderNodeAddress(),
                request.getPubKeyRing(),
                request.getTradeAmount(),
                request.getTradePrice(),
                request.getTradeFee(),
                request.getAccountId(),
                request.getPaymentAccountId(),
                request.getPaymentMethodId(),
                request.getUid(),
                request.getMessageVersion(),
                request.getAccountAgeWitnessSignatureOfOfferId(),
                request.getCurrentDate(),
                request.getMakerNodeAddress(),
                request.getTakerNodeAddress(),
                null,
                null,
                null,
                null,
                request.getPayoutAddress(),
                null
                );
        
        // get trade request as string
        String tradeRequestAsJson = Utilities.objectToJson(signedRequest);
        
        // verify maker signature
        try {
            return Sig.verify(makerPubKeyRing.getSignaturePubKey(),
                    tradeRequestAsJson,
                    signature);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Create a transaction to reserve a trade and freeze its funds. The deposit
     * amount is returned to the sender's payout address. Additional funds are
     * reserved to allow fluctuations in the mining fee.
     * 
     * @param xmrWalletService
     * @param offerId
     * @param tradeFee
     * @param depositAmount
     * @return a transaction to reserve a trade
     */
    public static MoneroTxWallet reserveTradeFunds(XmrWalletService xmrWalletService, String offerId, BigInteger tradeFee, String returnAddress, BigInteger depositAmount) {

        // get expected mining fee
        MoneroWallet wallet = xmrWalletService.getWallet();
        MoneroTxWallet miningFeeTx = wallet.createTx(new MoneroTxConfig()
                .setAccountIndex(0)
                .addDestination(TradeUtils.FEE_ADDRESS, tradeFee)
                .addDestination(returnAddress, depositAmount));
        BigInteger miningFee = miningFeeTx.getFee();

        // create reserve tx
        MoneroTxWallet reserveTx = wallet.createTx(new MoneroTxConfig()
                .setAccountIndex(0)
                .addDestination(TradeUtils.FEE_ADDRESS, tradeFee)
                .addDestination(returnAddress, depositAmount.add(miningFee.multiply(BigInteger.valueOf(3l))))); // add thrice the mining fee // TODO (woodser): really require more funds on top of security deposit?

        // freeze trade funds
        for (MoneroOutput input : reserveTx.getInputs()) {
            wallet.freezeOutput(input.getKeyImage().getHex());
        }

        return reserveTx;
    }
    
    /**
     * Create a transaction to deposit funds to the multisig wallet.
     * 
     * @param xmrWalletService
     * @param tradeFee
     * @param destinationAddress
     * @param depositAddress
     * @return MoneroTxWallet
     */
    public static MoneroTxWallet createDepositTx(XmrWalletService xmrWalletService, BigInteger tradeFee, String depositAddress, BigInteger depositAmount) {
        return xmrWalletService.getWallet().createTx(new MoneroTxConfig()
                .setAccountIndex(0)
                .addDestination(TradeUtils.FEE_ADDRESS, tradeFee)
                .addDestination(depositAddress, depositAmount));
    }
    
    /**
     * Process a reserve or deposit transaction used during trading.
     * Checks double spends, deposit amount and destination, trade fee, and mining fee.
     * The transaction is submitted but not relayed to the pool then flushed.
     * 
     * @param daemon is the Monero daemon to check for double spends
     * @param wallet is the Monero wallet to verify the tx
     * @param depositAddress is the expected destination address for the deposit amount
     * @param depositAmount is the expected amount deposited to multisig
     * @param tradeFee is the expected fee for trading
     * @param txHash is the transaction hash
     * @param txHex is the transaction hex
     * @param txKey is the transaction key
     * @param keyImages are expected key images of inputs, ignored if null
     * @param miningFeePadding verifies depositAmount has additional funds to cover mining fee increase
     */
    public static void processTradeTx(MoneroDaemon daemon, MoneroWallet wallet, String depositAddress, BigInteger depositAmount, BigInteger tradeFee, String txHash, String txHex, String txKey, List<String> keyImages, boolean miningFeePadding) {
        boolean submittedToPool = false;
        try {
            
            // get tx from daemon
            MoneroTx tx = daemon.getTx(txHash);
            
            // if tx is not submitted, submit but do not relay
            if (tx == null) {
                MoneroSubmitTxResult result = daemon.submitTxHex(txHex, true); // TODO (woodser): invert doNotRelay flag to relay for library consistency?
                if (!result.isGood()) throw new RuntimeException("Failed to submit tx to daemon: " + JsonUtils.serialize(result));
                submittedToPool = true;
                tx = daemon.getTx(txHash);
            } else if (tx.isRelayed()) {
                throw new RuntimeException("Trade tx must not be relayed");
            }
            
            // verify reserved key images
            if (keyImages != null) {
                Set<String> txKeyImages = new HashSet<String>();
                for (MoneroOutput input : tx.getInputs()) txKeyImages.add(input.getKeyImage().getHex());
                if (!txKeyImages.equals(new HashSet<String>(keyImages))) throw new Error("Reserve tx's inputs do not match claimed key images");
            }
            
            // verify the unlock height
            if (tx.getUnlockHeight() != 0) throw new RuntimeException("Unlock height must be 0");

            // verify trade fee
            String feeAddress = TradeUtils.FEE_ADDRESS;
            MoneroCheckTx check = wallet.checkTxKey(txHash, txKey, feeAddress);
            if (!check.isGood()) throw new RuntimeException("Invalid proof of trade fee");
            if (!check.getReceivedAmount().equals(tradeFee)) throw new RuntimeException("Trade fee is incorrect amount, expected " + tradeFee + " but was " + check.getReceivedAmount());

            // verify mining fee
            BigInteger feeEstimate = daemon.getFeeEstimate().multiply(BigInteger.valueOf(txHex.length())); // TODO (woodser): fee estimates are too high, use more accurate estimate
            BigInteger feeThreshold = feeEstimate.multiply(BigInteger.valueOf(1l)).divide(BigInteger.valueOf(2l)); // must be at least 50% of estimated fee
            tx = daemon.getTx(txHash);
            if (tx.getFee().compareTo(feeThreshold) < 0) {
                throw new RuntimeException("Mining fee is not enough, needed " + feeThreshold + " but was " + tx.getFee());
            }

            // verify deposit amount
            check = wallet.checkTxKey(txHash, txKey, depositAddress);
            if (!check.isGood()) throw new RuntimeException("Invalid proof of deposit amount");
            BigInteger depositThreshold = depositAmount;
            if (miningFeePadding) depositThreshold  = depositThreshold.add(feeThreshold.multiply(BigInteger.valueOf(3l))); // prove reserve of at least deposit amount + (3 * min mining fee)
            if (check.getReceivedAmount().compareTo(depositThreshold) < 0) throw new RuntimeException("Deposit amount is not enough, needed " + depositThreshold + " but was " + check.getReceivedAmount());
        } finally {
            
            // flush tx from pool if we added it
            if (submittedToPool) daemon.flushTxPool(txHash);
        }
    }
    
    /**
     * Create a contract from a trade.
     * 
     * TODO (woodser): refactor/reduce trade, process model, and trading peer models
     * 
     * @param trade is the trade to create the contract from
     * @return the contract
     */
    public static Contract createContract(Trade trade) {
        boolean isBuyerMakerAndSellerTaker = trade.getOffer().getDirection() == Direction.BUY;
        Contract contract = new Contract(
                trade.getOffer().getOfferPayload(),
                checkNotNull(trade.getTradeAmount()).value,
                trade.getTradePrice().getValue(),
                isBuyerMakerAndSellerTaker ? trade.getMakerNodeAddress() : trade.getTakerNodeAddress(), // buyer node address // TODO (woodser): use maker and taker node address instead of buyer and seller node address for consistency
                isBuyerMakerAndSellerTaker ? trade.getTakerNodeAddress() : trade.getMakerNodeAddress(), // seller node address
                trade.getArbitratorNodeAddress(),
                isBuyerMakerAndSellerTaker,
                trade instanceof MakerTrade ? trade.getProcessModel().getAccountId() : trade.getMaker().getAccountId(), // maker account id
                trade instanceof TakerTrade ? trade.getProcessModel().getAccountId() : trade.getTaker().getAccountId(), // taker account id
                checkNotNull(trade instanceof MakerTrade ? trade.getProcessModel().getPaymentAccountPayload(trade).getPaymentMethodId() : trade.getOffer().getOfferPayload().getPaymentMethodId()), // maker payment method id
                checkNotNull(trade instanceof TakerTrade ? trade.getProcessModel().getPaymentAccountPayload(trade).getPaymentMethodId() : trade.getTaker().getPaymentMethodId()), // taker payment method id
                trade instanceof MakerTrade ? trade.getProcessModel().getPaymentAccountPayload(trade).getHash() : trade.getMaker().getPaymentAccountPayloadHash(), // maker payment account payload hash
                trade instanceof TakerTrade ? trade.getProcessModel().getPaymentAccountPayload(trade).getHash() : trade.getTaker().getPaymentAccountPayloadHash(), // maker payment account payload hash
                trade.getMakerPubKeyRing(),
                trade.getTakerPubKeyRing(),
                trade instanceof MakerTrade ? trade.getXmrWalletService().getAddressEntry(trade.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString() : trade.getMaker().getPayoutAddressString(), // maker payout address
                trade instanceof TakerTrade ? trade.getXmrWalletService().getAddressEntry(trade.getId(), XmrAddressEntry.Context.TRADE_PAYOUT).get().getAddressString() : trade.getTaker().getPayoutAddressString(), // taker payout address
                trade.getLockTime(),
                trade.getMaker().getDepositTxHash(),
                trade.getTaker().getDepositTxHash()
        );
        return contract;
    }
    
    // TODO (woodser): remove the following utitilites?

    // Returns <MULTI_SIG, TRADE_PAYOUT> if both are AVAILABLE, otherwise null
    static Tuple2<String, String> getAvailableAddresses(Trade trade, XmrWalletService xmrWalletService,
                                                        KeyRing keyRing) {
        var addresses = getTradeAddresses(trade, xmrWalletService, keyRing);
        if (addresses == null)
            return null;

        if (xmrWalletService.getAvailableAddressEntries().stream()
                .noneMatch(e -> Objects.equals(e.getAddressString(), addresses.first)))
            return null;
        if (xmrWalletService.getAvailableAddressEntries().stream()
                .noneMatch(e -> Objects.equals(e.getAddressString(), addresses.second)))
            return null;

        return new Tuple2<>(addresses.first, addresses.second);
    }

    // Returns <MULTI_SIG, TRADE_PAYOUT> addresses as strings if they're known by the wallet
    public static Tuple2<String, String> getTradeAddresses(Trade trade, XmrWalletService xmrWalletService,
                                                           KeyRing keyRing) {
        var contract = trade.getContract();
        if (contract == null)
            return null;

        // TODO (woodser): xmr multisig does not use pub key
        throw new RuntimeException("need to replace btc multisig pub key with xmr");

        // Get multisig address
//        var isMyRoleBuyer = contract.isMyRoleBuyer(keyRing.getPubKeyRing());
//        var multiSigPubKey = isMyRoleBuyer ? contract.getBuyerMultiSigPubKey() : contract.getSellerMultiSigPubKey();
//        if (multiSigPubKey == null)
//            return null;
//        var multiSigPubKeyString = Utilities.bytesAsHexString(multiSigPubKey);
//        var multiSigAddress = xmrWalletService.getAddressEntryListAsImmutableList().stream()
//                .filter(e -> e.getKeyPair().getPublicKeyAsHex().equals(multiSigPubKeyString))
//                .findAny()
//                .orElse(null);
//        if (multiSigAddress == null)
//            return null;
//
//        // Get payout address
//        var payoutAddress = isMyRoleBuyer ?
//                contract.getBuyerPayoutAddressString() : contract.getSellerPayoutAddressString();
//        var payoutAddressEntry = xmrWalletService.getAddressEntryListAsImmutableList().stream()
//                .filter(e -> Objects.equals(e.getAddressString(), payoutAddress))
//                .findAny()
//                .orElse(null);
//        if (payoutAddressEntry == null)
//            return null;
//
//        return new Tuple2<>(multiSigAddress.getAddressString(), payoutAddress);
    }
}
