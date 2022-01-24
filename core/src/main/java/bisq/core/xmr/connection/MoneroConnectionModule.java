package bisq.core.xmr.connection;

import bisq.common.app.AppModule;
import bisq.common.config.Config;
import bisq.core.api.CoreMoneroConnectionsService;
import bisq.core.btc.model.EncryptedConnectionList;
import com.google.inject.Singleton;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MoneroConnectionModule extends AppModule {

    public MoneroConnectionModule(Config config) {
        super(config);
    }

    @Override
    protected final void configure() {
        bind(EncryptedConnectionList.class).in(Singleton.class);
        bind(CoreMoneroConnectionsService.class).in(Singleton.class);
    }
}
