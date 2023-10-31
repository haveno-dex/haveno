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

package haveno.core.trade;

import haveno.core.support.dispute.Dispute;
import haveno.core.xmr.wallet.BtcWalletService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

// TODO: remove for XMR?

@Slf4j
public class TradeDataValidation {

    public static void validateDelayedPayoutTx(Trade trade,
                                               Transaction delayedPayoutTx,
                                               BtcWalletService btcWalletService)
            throws AddressException, MissingTxException,
            InvalidTxException, InvalidLockTimeException, InvalidAmountException {
        validateDelayedPayoutTx(trade,
                delayedPayoutTx,
                null,
                btcWalletService,
                null);
    }

    public static void validateDelayedPayoutTx(Trade trade,
                                               Transaction delayedPayoutTx,
                                               @Nullable Dispute dispute,
                                               BtcWalletService btcWalletService)
            throws AddressException, MissingTxException,
            InvalidTxException, InvalidLockTimeException, InvalidAmountException {
        validateDelayedPayoutTx(trade,
                delayedPayoutTx,
                dispute,
                btcWalletService,
                null);
    }

    public static void validateDelayedPayoutTx(Trade trade,
                                               Transaction delayedPayoutTx,
                                               BtcWalletService btcWalletService,
                                               @Nullable Consumer<String> addressConsumer)
            throws AddressException, MissingTxException,
            InvalidTxException, InvalidLockTimeException, InvalidAmountException {
        validateDelayedPayoutTx(trade,
                delayedPayoutTx,
                null,
                btcWalletService,
                addressConsumer);
    }

    public static void validateDelayedPayoutTx(Trade trade,
                                               Transaction delayedPayoutTx,
                                               @Nullable Dispute dispute,
                                               BtcWalletService btcWalletService,
                                               @Nullable Consumer<String> addressConsumer)
            throws AddressException, MissingTxException,
            InvalidTxException, InvalidLockTimeException, InvalidAmountException {
        String errorMsg;
        if (delayedPayoutTx == null) {
            errorMsg = "DelayedPayoutTx must not be null";
            log.error(errorMsg);
            throw new MissingTxException("DelayedPayoutTx must not be null");
        }

        // Validate tx structure
        if (delayedPayoutTx.getInputs().size() != 1) {
            errorMsg = "Number of delayedPayoutTx inputs must be 1";
            log.error(errorMsg);
            log.error(delayedPayoutTx.toString());
            throw new InvalidTxException(errorMsg);
        }

        if (delayedPayoutTx.getOutputs().size() != 1) {
            errorMsg = "Number of delayedPayoutTx outputs must be 1";
            log.error(errorMsg);
            log.error(delayedPayoutTx.toString());
            throw new InvalidTxException(errorMsg);
        }

        // connectedOutput is null and input.getValue() is null at that point as the tx is not committed to the wallet
        // yet. So we cannot check that the input matches but we did the amount check earlier in the trade protocol.

        // Validate lock time
        if (delayedPayoutTx.getLockTime() != trade.getLockTime()) {
            errorMsg = "delayedPayoutTx.getLockTime() must match trade.getLockTime()";
            log.error(errorMsg);
            log.error(delayedPayoutTx.toString());
            throw new InvalidLockTimeException(errorMsg);
        }

        // Validate seq num
        if (delayedPayoutTx.getInput(0).getSequenceNumber() != TransactionInput.NO_SEQUENCE - 1) {
            errorMsg = "Sequence number must be 0xFFFFFFFE";
            log.error(errorMsg);
            log.error(delayedPayoutTx.toString());
            throw new InvalidLockTimeException(errorMsg);
        }

        // Check amount
        TransactionOutput output = delayedPayoutTx.getOutput(0);
        BigInteger msOutputAmount = trade.getBuyerSecurityDepositBeforeMiningFee()
                .add(trade.getSellerSecurityDepositBeforeMiningFee())
                .add(checkNotNull(trade.getAmount()));

        if (!output.getValue().equals(msOutputAmount)) {
            errorMsg = "Output value of deposit tx and delayed payout tx is not matching. Output: " + output + " / msOutputAmount: " + msOutputAmount;
            log.error(errorMsg);
            log.error(delayedPayoutTx.toString());
            throw new InvalidAmountException(errorMsg);
        }

        NetworkParameters params = btcWalletService.getParams();
        Address address = output.getScriptPubKey().getToAddress(params);
        if (address == null) {
            errorMsg = "Donation address cannot be resolved (not of type P2PK nor P2SH nor P2WH). Output: " + output;
            log.error(errorMsg);
            log.error(delayedPayoutTx.toString());
            throw new AddressException(dispute, errorMsg);
        }

        String addressAsString = address.toString();
        if (addressConsumer != null) {
            addressConsumer.accept(addressAsString);
        }

        if (dispute != null) {
            // Verify that address in the dispute matches the one in the trade.
            String donationAddressOfDelayedPayoutTx = dispute.getDonationAddressOfDelayedPayoutTx();
            // Old clients don't have it set yet. Can be removed after a forced update
            if (donationAddressOfDelayedPayoutTx != null) {
                checkArgument(addressAsString.equals(donationAddressOfDelayedPayoutTx),
                        "donationAddressOfDelayedPayoutTx from dispute does not match address from delayed payout tx");
            }
        }
    }

    public static void validatePayoutTxInput(Transaction depositTx,
                                             Transaction delayedPayoutTx)
            throws InvalidInputException {
        TransactionInput input = delayedPayoutTx.getInput(0);
        checkNotNull(input, "delayedPayoutTx.getInput(0) must not be null");
        // input.getConnectedOutput() is null as the tx is not committed at that point

        TransactionOutPoint outpoint = input.getOutpoint();
        if (!outpoint.getHash().toString().equals(depositTx.getTxId().toString()) || outpoint.getIndex() != 0) {
            throw new InvalidInputException("Input of delayed payout transaction does not point to output of deposit tx.\n" +
                    "Delayed payout tx=" + delayedPayoutTx + "\n" +
                    "Deposit tx=" + depositTx);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Exceptions
    ///////////////////////////////////////////////////////////////////////////////////////////

    public static class ValidationException extends Exception {
        @Nullable
        @Getter
        private final Dispute dispute;

        ValidationException(String msg) {
            this(null, msg);
        }

        ValidationException(@Nullable Dispute dispute, String msg) {
            super(msg);
            this.dispute = dispute;
        }
    }

    public static class InvalidPaymentAccountPayloadException extends ValidationException {
        InvalidPaymentAccountPayloadException(@Nullable Dispute dispute, String msg) {
            super(dispute, msg);
        }
    }

    public static class AddressException extends ValidationException {
        AddressException(@Nullable Dispute dispute, String msg) {
            super(dispute, msg);
        }
    }

    public static class MissingTxException extends ValidationException {
        MissingTxException(String msg) {
            super(msg);
        }
    }

    public static class InvalidTxException extends ValidationException {
        InvalidTxException(String msg) {
            super(msg);
        }
    }

    public static class InvalidAmountException extends ValidationException {
        InvalidAmountException(String msg) {
            super(msg);
        }
    }

    public static class InvalidLockTimeException extends ValidationException {
        InvalidLockTimeException(String msg) {
            super(msg);
        }
    }

    public static class InvalidInputException extends ValidationException {
        InvalidInputException(String msg) {
            super(msg);
        }
    }

    public static class DisputeReplayException extends ValidationException {
        DisputeReplayException(Dispute dispute, String msg) {
            super(dispute, msg);
        }
    }

    public static class NodeAddressException extends ValidationException {
        NodeAddressException(Dispute dispute, String msg) {
            super(dispute, msg);
        }
    }
}
