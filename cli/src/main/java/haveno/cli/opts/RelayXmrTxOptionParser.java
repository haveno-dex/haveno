package haveno.cli.opts;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

public class RelayXmrTxOptionParser extends OptionParser {

    public RelayXmrTxOptionParser(String[] args) {
        accepts("metadata", "Metadata for the XMR transaction")
                .withRequiredArg()
                .ofType(String.class);
    }

    public OptionSet parse(String[] args) {
        return super.parse(args);
    }

    public static class RelayXmrTxOptions {
        private final OptionSet options;

        public RelayXmrTxOptions(OptionSet options) {
            this.options = options;
        }

        public boolean isForHelp() {
            return options.has("help");
        }

        public String getMetadata() {
            return (String) options.valueOf("metadata");
        }
    }
}
