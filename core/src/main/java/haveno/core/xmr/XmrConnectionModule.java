package haveno.core.xmr;

import com.google.inject.Singleton;
import haveno.common.app.AppModule;
import haveno.common.config.Config;
import haveno.core.api.XmrConnectionService;
import haveno.core.xmr.model.EncryptedConnectionList;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class XmrConnectionModule extends AppModule {

    public XmrConnectionModule(Config config) {
        super(config);
    }

    @Override
    protected final void configure() {
        bind(EncryptedConnectionList.class).in(Singleton.class);
        bind(XmrConnectionService.class).in(Singleton.class);
    }
}
