package haveno.core.locale;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SepaCountriesTest {

    @Test
    public void sepaEuroListContainsMontenegroAndKosovoWithNames() {
        List<Country> countries = CountryUtil.getAllSepaEuroCountries();
        List<String> codes = countries.stream().map(c -> c.code).collect(Collectors.toList());
        assertTrue(codes.contains("ME"));
        assertTrue(codes.contains("XK"));
        for (Country country : countries) {
            assertFalse(country.name.isEmpty(), "missing display name for " + country.code);
            assertFalse(country.name.equals(country.code), "unresolved display name for " + country.code);
        }
    }

    @Test
    public void sepaNonEuroListNoLongerContainsMontenegro() {
        List<String> codes = CountryUtil.getAllSepaNonEuroCountries().stream().map(c -> c.code).collect(Collectors.toList());
        assertFalse(codes.contains("ME"));
    }
}
