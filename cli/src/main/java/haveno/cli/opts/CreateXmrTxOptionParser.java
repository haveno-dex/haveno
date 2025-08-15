package haveno.cli.opts;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import java.util.ArrayList;
import java.util.List;
import haveno.proto.grpc.XmrDestination;

public class CreateXmrTxOptionParser extends OptionParser {

    public CreateXmrTxOptionParser(String[] args) {
        accepts("destinations", "List of destinations for the XMR transaction")
                .withRequiredArg()
                .ofType(String.class);
    }

    public OptionSet parse(String[] args) {
        return super.parse(args);
    }

    public static class CreateXmrTxOptions {
        private final OptionSet options;

        public CreateXmrTxOptions(OptionSet options) {
            this.options = options;
        }

        public boolean isForHelp() {
            return options.has("help");
        }

        public List<XmrDestination> getDestinations() {
            List<XmrDestination> destinations = new ArrayList<>();
            // Parse the destinations from the options and populate the list
            // This is a placeholder; you need to implement the actual parsing logic
            return destinations;
        }
    }
}
