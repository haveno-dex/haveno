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

package haveno.core.trade.txproof;

import lombok.Getter;

/**
 * Result of verifying a crypto payment proof. Only VERIFIED may trigger auto-confirmation.
 */
@Getter
public final class CryptoTxProofResult {

    public enum Status {
        VERIFIED,
        PENDING,
        FAILED,
        ERROR
    }

    private final Status status;
    private final String details;
    private final int confirmations;

    private CryptoTxProofResult(Status status, String details, int confirmations) {
        this.status = status;
        this.details = details;
        this.confirmations = confirmations;
    }

    public static CryptoTxProofResult verified(int confirmations) {
        return new CryptoTxProofResult(Status.VERIFIED, "ok", confirmations);
    }

    public static CryptoTxProofResult pending(String details, int confirmations) {
        return new CryptoTxProofResult(Status.PENDING, details, confirmations);
    }

    public static CryptoTxProofResult failed(String details) {
        return new CryptoTxProofResult(Status.FAILED, details, 0);
    }

    public static CryptoTxProofResult error(String details) {
        return new CryptoTxProofResult(Status.ERROR, details, 0);
    }

    public boolean isVerified() {
        return status == Status.VERIFIED;
    }

    public boolean isFinal() {
        return status == Status.VERIFIED || status == Status.FAILED;
    }

    @Override
    public String toString() {
        return status + " (" + details + ", confirmations=" + confirmations + ")";
    }
}
