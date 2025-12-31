package haveno.cli.table.column;

import java.util.stream.IntStream;

import static com.google.common.base.Strings.padEnd;
import static haveno.cli.CurrencyFormat.formatXmr;
import static java.util.Comparator.comparingInt;

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
        // We cached the formatted piconero strings, but we did
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
