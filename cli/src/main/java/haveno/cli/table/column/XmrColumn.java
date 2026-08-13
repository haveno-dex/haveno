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

import java.util.stream.IntStream;

import static com.google.common.base.Strings.padEnd;
import static haveno.cli.CurrencyFormat.formatXmr;
import static java.util.Comparator.comparingInt;

/**
 * For displaying XMR amounts with variable precision, zero padded to the column's widest value.
 */
public class XmrColumn extends PiconeroColumn {

    public XmrColumn(String name) {
        super(name);
    }

    @Override
    public void addRow(Long value) {
        rows.add(value);

        String s = formatXmr(value);
        stringColumn.addRow(s);

        if (isNewMaxWidth.test(s))
            maxWidth = s.length();
    }

    @Override
    public String getRowAsFormattedString(int rowIndex) {
        return formatXmr(getRow(rowIndex));
    }

    @Override
    public StringColumn asStringColumn() {
        // We cached the formatted XMR strings, but we did
        // not know how much zero padding each string needed until now.
        int maxColumnValueWidth = stringColumn.getRows().stream()
                .max(comparingInt(String::length))
                .get()
                .length();
        IntStream.range(0, stringColumn.getRows().size()).forEach(rowIndex -> {
            String xmrString = stringColumn.getRow(rowIndex);
            if (xmrString.length() < maxColumnValueWidth) {
                String paddedXmrString = padEnd(xmrString, maxColumnValueWidth, '0');
                stringColumn.updateRow(rowIndex, paddedXmrString);
            }
        });
        return stringColumn.justify();
    }
}
