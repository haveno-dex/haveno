package bisq.common.config;

import bisq.common.HavenoException;

public class ConfigException extends HavenoException {

    public ConfigException(String format, Object... args) {
        super(format, args);
    }
}
