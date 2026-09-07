package fr.tofuxia.desktop;

import fr.tofuxia.app.EngineArgs;
import fr.tofuxia.app.NetworkStatusProvider;
import io.github.juloass.content.ContentBootstrapResult;
import io.github.juloass.localization.Localization;

/** Optional application-owned integration between the desktop host and a network protocol. */
@FunctionalInterface
public interface DesktopNetworkFactory {
    DesktopNetworkFactory OFFLINE = (args, content, settings, localization) -> NetworkStatusProvider.NONE;

    NetworkStatusProvider create(
            EngineArgs args,
            ContentBootstrapResult content,
            DesktopClientSettings settings,
            Localization localization);
}
