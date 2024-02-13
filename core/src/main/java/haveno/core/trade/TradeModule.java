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

package haveno.core.trade;

import com.google.inject.Singleton;
import haveno.common.app.AppModule;
import haveno.common.config.Config;
import haveno.core.account.sign.SignedWitnessService;
import haveno.core.account.sign.SignedWitnessStorageService;
import haveno.core.account.witness.AccountAgeWitnessService;
import haveno.core.account.witness.AccountAgeWitnessStorageService;
import haveno.core.trade.failed.FailedTradesManager;
import haveno.core.trade.statistics.ReferralIdService;

import static com.google.inject.name.Names.named;
import static haveno.common.config.Config.DUMP_STATISTICS;

public class TradeModule extends AppModule {

    public TradeModule(Config config) {
        super(config);
    }

    @Override
    protected void configure() {
        bind(TradeManager.class).in(Singleton.class);
        bind(ClosedTradableManager.class).in(Singleton.class);
        bind(FailedTradesManager.class).in(Singleton.class);
        bind(AccountAgeWitnessService.class).in(Singleton.class);
        bind(AccountAgeWitnessStorageService.class).in(Singleton.class);
        bind(SignedWitnessService.class).in(Singleton.class);
        bind(SignedWitnessStorageService.class).in(Singleton.class);
        bind(ReferralIdService.class).in(Singleton.class);

        bindConstant().annotatedWith(named(DUMP_STATISTICS)).to(config.dumpStatistics);
    }
}
