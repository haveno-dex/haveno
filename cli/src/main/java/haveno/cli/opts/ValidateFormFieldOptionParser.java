/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.cli.opts;

import joptsimple.OptionSpec;
import lombok.Getter;

import static haveno.cli.opts.OptLabel.OPT_FORM;
import static haveno.cli.opts.OptLabel.OPT_FIELD_ID;
import static haveno.cli.opts.OptLabel.OPT_VALUE;

public class ValidateFormFieldOptionParser extends AbstractMethodOptionParser {

    @Getter
    private final OptionSpec<String> formOpt = parser.accepts(OPT_FORM, "Form")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> fieldIdOpt = parser.accepts(OPT_FIELD_ID, "Field ID")
            .withRequiredArg()
            .required();

    @Getter
    private final OptionSpec<String> valueOpt = parser.accepts(OPT_VALUE, "Value")
            .withRequiredArg()
            .required();

    public ValidateFormFieldOptionParser(String[] args) {
        super(args);
    }

    public String getForm() {
        return options.valueOf(formOpt);
    }

    public String getFieldId() {
        return options.valueOf(fieldIdOpt);
    }

    public String getValue() {
        return options.valueOf(valueOpt);
    }
}
