package haveno.core.filter;

import org.junit.jupiter.api.Test;

import static haveno.core.filter.FilterManager.normalizeBannedValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FilterManagerTest {

    @Test
    public void testNumericIdentifiersIgnoreAllFormatting() {
        assertEquals("1234567890", normalizeBannedValue("123-456-7890"));
        assertEquals("0123456789", normalizeBannedValue("0123 456 789"));
        assertEquals("584121234567", normalizeBannedValue("+58 412 123 45 67"));
        assertEquals("12345678903", normalizeBannedValue("1234.56.78903"));
        assertTrue(normalizeBannedValue("   ").isEmpty()); // blank values never match
        assertNotEquals(normalizeBannedValue("1234567891"), normalizeBannedValue("123-456-7890")); // distinct accounts stay distinct
    }

    @Test
    public void testNonNumericIdentifiersKeepSignificantPunctuation() {
        assertNotEquals(normalizeBannedValue("john.doe@x.com"), normalizeBannedValue("johndoe@x.com")); // distinct mailboxes stay distinct
        assertNotEquals(normalizeBannedValue("john-doe"), normalizeBannedValue("johndoe")); // distinct usernames stay distinct
        assertEquals("john.doe@x.com", normalizeBannedValue(" john.doe@x.com "));
        assertEquals("DE89370400440532013000", normalizeBannedValue("DE89 3704 0044 0532 0130 00")); // whitespace is never significant
        assertEquals("MaríaPérez", normalizeBannedValue("María Pérez")); // letters are preserved, including accents
    }

    @Test
    public void testFormattingVariantsMatchSameAccount() {
        assertTrue(normalizeBannedValue("123-456-7890").equalsIgnoreCase(normalizeBannedValue("1234567890")));
        assertTrue(normalizeBannedValue("DE89 3704 0044 0532 0130 00").equalsIgnoreCase(normalizeBannedValue("de89370400440532013000")));
        assertTrue(normalizeBannedValue("JOHN.DOE@X.COM").equalsIgnoreCase(normalizeBannedValue("john.doe@x.com")));
    }
}
