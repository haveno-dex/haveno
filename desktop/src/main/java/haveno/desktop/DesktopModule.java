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

package haveno.desktop;

import static haveno.common.config.Config.APP_NAME;

import com.google.inject.Singleton;
import com.google.inject.name.Names;
import haveno.common.app.AppModule;
import haveno.common.config.Config;
import haveno.core.locale.Res;
import haveno.desktop.common.fxml.FxmlViewLoader;
import haveno.desktop.common.view.ViewFactory;
import haveno.desktop.common.view.ViewLoader;
import haveno.desktop.common.view.guice.InjectorViewFactory;
import java.util.ResourceBundle;

public class DesktopModule extends AppModule {

    public DesktopModule(Config config) {
        super(config);
    }

    @Override
    protected void configure() {
        bind(ViewFactory.class).to(InjectorViewFactory.class);

        bind(ResourceBundle.class).toInstance(Res.getResourceBundle());
        bind(ViewLoader.class).to(FxmlViewLoader.class).in(Singleton.class);

        bindConstant().annotatedWith(Names.named(APP_NAME)).to(config.appName);
    }
}
