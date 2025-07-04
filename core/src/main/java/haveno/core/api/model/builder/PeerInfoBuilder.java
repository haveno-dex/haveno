package haveno.core.api.model.builder;

import haveno.core.api.model.PeerInfo;

import java.util.List;



/*
 * A builder helps avoid bungling use of a large OfferInfo constructor
 * argument list.  If consecutive argument values of the same type are not
 * ordered correctly, the compiler won't complain but the resulting bugs could
 * be hard to find and fix.
 */

public final class PeerInfoBuilder {

    private String nodeAddress;

    private List<Integer> capabilities;

    public PeerInfoBuilder withNodeAddress(String nodeAddress) {
        this.nodeAddress = nodeAddress;
        return this;
    }

    public PeerInfoBuilder withCapabilities(List<Integer> capabilities) {
        this.capabilities = capabilities;
        return this;
    }

    public PeerInfo build() {
        return new PeerInfo(this);
    }

    public String getNodeAddress() {
        return nodeAddress;
    }

    public List<Integer> getCapabilities() {
        return capabilities;
    }

}
