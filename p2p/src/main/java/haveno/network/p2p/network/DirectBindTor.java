package haveno.network.p2p.network;

import lombok.extern.slf4j.Slf4j;
import org.berndpruenster.netlayer.tor.Tor;

@Slf4j
public class DirectBindTor extends TorMode {

    public DirectBindTor() {
        super(null);
    }

    @Override
    public Tor getTor() {
        return null;
    }

    @Override
    public String getHiddenServiceDirectory() {
        return null;
    }
}
