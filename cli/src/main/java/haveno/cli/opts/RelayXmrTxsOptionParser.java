package haveno.cli.opts;

import joptsimple.OptionSet;

import java.util.List;

public class RelayXmrTxsOptionParser extends AbstractMethodOptionParser {

    public RelayXmrTxsOptionParser(String[] args) {
        super(args);
        parser.accepts("metadatas", "Metadata for the XMR transactions")
                .withRequiredArg()
                .ofType(String.class)
                .withValuesSeparatedBy(',');
    }

    @Override
    public RelayXmrTxsOptionParser parse() {
        super.parse();
        return this;
    }

    public RelayXmrTxsOptions getRelayXmrTxsOptions() {
        return new RelayXmrTxsOptions(options);
    }

    public static class RelayXmrTxsOptions {
        private final OptionSet options;

        public RelayXmrTxsOptions(OptionSet options) {
            this.options = options;
        }

        public boolean isForHelp() {
            return options.has("help");
        }

        public List<String> getMetadatas() {
            return (List<String>) options.valuesOf("metadatas");
        }
    }
}
