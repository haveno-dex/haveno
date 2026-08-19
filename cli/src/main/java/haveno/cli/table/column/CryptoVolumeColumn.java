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

package haveno.cli.table.column;

import java.math.BigDecimal;
import java.util.function.Function;
import java.util.stream.IntStream;

import static haveno.cli.table.column.Column.JUSTIFICATION.RIGHT;

/**
 * For displaying crypto currency volumes, which are held in units of 10^-8.
 */
public class CryptoVolumeColumn extends LongColumn {

    // The default CryptoVolumeColumn JUSTIFICATION is RIGHT.
    public CryptoVolumeColumn(String name) {
        this(name, RIGHT);
    }

    public CryptoVolumeColumn(String name, JUSTIFICATION justification) {
        super(name, justification);
    }

    @Override
    public void addRow(Long value) {
        rows.add(value);

        String s = toFormattedString.apply(value);
        stringColumn.addRow(s);

        if (isNewMaxWidth.test(s))
            maxWidth = s.length();
    }

    @Override
    public String getRowAsFormattedString(int rowIndex) {
        return toFormattedString.apply(getRow(rowIndex));
    }

    @Override
    public StringColumn asStringColumn() {
        // We cached the formatted crypto value strings, but we did
        // not know how much padding each string needed until now.
        IntStream.range(0, stringColumn.getRows().size()).forEach(rowIndex -> {
            String unjustified = stringColumn.getRow(rowIndex);
            String justified = stringColumn.toJustifiedString(unjustified);
            stringColumn.updateRow(rowIndex, justified);
        });
        return this.stringColumn;
    }

    private final Function<Long, String> toFormattedString = (value) ->
            value > 0 ? new BigDecimal(value).movePointLeft(8).toString() : "";
}
