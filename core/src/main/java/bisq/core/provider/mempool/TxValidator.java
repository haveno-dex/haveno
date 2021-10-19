/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.provider.mempool;

import org.bitcoinj.core.Coin;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TxValidator {

    private final List<String> errorList;
    private final String txId;
    private Coin amount;
    @Setter
    private String jsonTxt;


    public TxValidator(String txId, Coin amount) {
        this.txId = txId;
        this.amount = amount;
        this.errorList = new ArrayList<>();
        this.jsonTxt = "";
    }

    public TxValidator(String txId) {
        this.txId = txId;
        this.errorList = new ArrayList<>();
        this.jsonTxt = "";
    }

    public TxValidator endResult(String title, boolean status) {
        log.info("{} : {}", title, status ? "SUCCESS" : "FAIL");
        if (!status) {
            errorList.add(title);
        }
        return this;
    }

    public boolean isFail() {
        return errorList.size() > 0;
    }

    public boolean getResult() {
        return errorList.size() == 0;
    }

    public String errorSummary() {
        return errorList.toString().substring(0, Math.min(85, errorList.toString().length()));
    }

    @Override
    public String toString() {
        return errorList.toString();
    }
}
