package haveno.common.config;

import haveno.common.HavenoException;

public class ConfigException extends HavenoException {

    public ConfigException(String format, Object... args) {
        super(format, args);
    }
}
