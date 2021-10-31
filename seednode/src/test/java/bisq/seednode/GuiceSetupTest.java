package haveno.seednode;

import haveno.core.app.misc.AppSetupWithP2PAndDAO;
import haveno.core.app.misc.ModuleForAppWithP2p;
import haveno.core.locale.CurrencyUtil;
import haveno.core.locale.Res;

import haveno.common.config.Config;

import com.google.inject.Guice;

import org.junit.Test;

public class GuiceSetupTest {
    @Test
    public void testGuiceSetup() {
        Res.setup();
        CurrencyUtil.setup();

        ModuleForAppWithP2p module = new ModuleForAppWithP2p(new Config());
        Guice.createInjector(module).getInstance(AppSetupWithP2PAndDAO.class);
    }
}
