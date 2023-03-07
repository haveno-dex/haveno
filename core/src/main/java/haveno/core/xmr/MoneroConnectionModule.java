package haveno.core.xmr;

import com.google.inject.Singleton;
import haveno.common.app.AppModule;
import haveno.common.config.Config;
import haveno.core.api.CoreMoneroConnectionsService;
import haveno.core.xmr.model.EncryptedConnectionList;
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
