package haveno.common.setup;

import org.apache.commons.lang3.Validate;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class CommonSetupTest {

    @Test
    public void sameSiteWithDifferentMessagesSharesFingerprint() {
        assertEquals(CommonSetup.throwSite(newException("id-1")), CommonSetup.throwSite(newException("id-2")));
        assertFalse(CommonSetup.throwSite(newException("id-1")).contains("id-1"));
    }

    @Test
    public void distinctCallersOfJdkHelperGetDistinctFingerprints() {
        assertNotEquals(CommonSetup.throwSite(npeFromHelperSiteA()), CommonSetup.throwSite(npeFromHelperSiteB()));
    }

    @Test
    public void distinctCallersOfLibraryHelperGetDistinctFingerprints() {
        assertNotEquals(CommonSetup.throwSite(blankFromHelperSiteA()), CommonSetup.throwSite(blankFromHelperSiteB()));
    }

    @Test
    public void sameWrapSiteWithDifferentCausesGetsDistinctFingerprints() {
        assertNotEquals(CommonSetup.throwSite(wrap(new IllegalStateException("x"))),
                CommonSetup.throwSite(wrap(new IllegalArgumentException("x"))));
    }

    @Test
    public void missingStackTraceStillProducesFingerprint() {
        RuntimeException exception = new RuntimeException("boom");
        exception.setStackTrace(new StackTraceElement[0]);
        assertEquals(RuntimeException.class.getName(), CommonSetup.throwSite(exception));
    }

    private RuntimeException newException(String message) {
        return new RuntimeException(message);
    }

    private RuntimeException wrap(Throwable cause) {
        return new RuntimeException("wrapped", cause);
    }

    private NullPointerException npeFromHelperSiteA() {
        try {
            Objects.requireNonNull(null);
            return null;
        } catch (NullPointerException e) {
            return e;
        }
    }

    private NullPointerException npeFromHelperSiteB() {
        try {
            Objects.requireNonNull(null);
            return null;
        } catch (NullPointerException e) {
            return e;
        }
    }

    private IllegalArgumentException blankFromHelperSiteA() {
        try {
            Validate.notBlank("");
            return null;
        } catch (IllegalArgumentException e) {
            return e;
        }
    }

    private IllegalArgumentException blankFromHelperSiteB() {
        try {
            Validate.notBlank("");
            return null;
        } catch (IllegalArgumentException e) {
            return e;
        }
    }
}
