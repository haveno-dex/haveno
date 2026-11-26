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

package haveno.core.payment;

import haveno.core.locale.Res;
import haveno.core.offer.Offer;
import haveno.core.payment.payload.CustomPaymentMethodAccountPayload;
import haveno.core.payment.payload.PaymentAccountPayload;
import haveno.core.payment.payload.PaymentMethod;

import javax.annotation.Nullable;
import java.text.Normalizer;
import java.util.Locale;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;

// Rules for the name and account data of a custom payment method, which are free texts shown to other traders
public class CustomPaymentMethodUtil {
    public static final int MAX_NAME_LENGTH = 20;
    public static final int MAX_ACCOUNT_DATA_LENGTH = 1000;

    // more marks above or below one character are drawn over the lines next to it
    public static final int MAX_MARKS_IN_A_ROW = 4;

    // needed inside words of scripts such as Persian or Devanagari
    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    // a valid name is one line which can be shown and compared
    public static boolean isValidName(@Nullable String name) {
        return name != null &&
                name.length() <= MAX_NAME_LENGTH &&
                hasOnlyAllowedCharacters(name, false) &&
                hasLetterOrNumber(toComparableName(name)) &&
                !hasTooManyMarksInARow(name);
    }

    public static boolean isValidAccountData(@Nullable String accountData) {
        return accountData != null &&
                accountData.length() <= MAX_ACCOUNT_DATA_LENGTH &&
                hasOnlyAllowedCharacters(accountData, true) &&
                hasLetterOrNumber(Normalizer.normalize(accountData, Normalizer.Form.NFKC));
    }

    // removes characters which are not allowed, most of them invisible, and the spaces around the text
    public static String cleanName(@Nullable String name) {
        return name == null ? "" : removeNotAllowedCharacters(name, false).strip();
    }

    public static String cleanAccountData(@Nullable String accountData) {
        return accountData == null ? "" : removeNotAllowedCharacters(accountData, true).strip();
    }

    // names are the same without regard to case, spaces, punctuation and symbols, so "Orange Money" equals "orange-money"
    public static boolean isSameName(@Nullable String name, @Nullable String otherName) {
        return isValidName(name) &&
                isValidName(otherName) &&
                toComparableName(name).equals(toComparableName(otherName));
    }

    // true if the name is a payment method's id, name or short name in the user's language or English, retired ones too
    @SuppressWarnings("deprecation")
    public static boolean isNameOfPaymentMethod(String name) {
        String comparableName = toComparableName(name);
        return Stream.concat(PaymentMethod.paymentMethods.stream().map(PaymentMethod::getId),
                        Stream.of(PaymentMethod.OK_PAY_ID, PaymentMethod.CHASE_QUICK_PAY_ID, PaymentMethod.POPMONEY_ID))
                .flatMap(CustomPaymentMethodUtil::getNamesOfPaymentMethod)
                .map(CustomPaymentMethodUtil::toComparableName)
                .anyMatch(comparableName::equals);
    }

    // The peer's revealed account must be for the custom payment method of the offer, or for none if the offer has none.
    // Messages do not contain texts of the peer.
    public static void checkPeerPaymentAccountPayload(PaymentAccountPayload peerPaymentAccountPayload, Offer offer) {
        boolean isCustomOffer = offer.isCustomPaymentMethod();
        checkArgument(isCustomOffer == peerPaymentAccountPayload instanceof CustomPaymentMethodAccountPayload,
                "Peer's payment account and the offer must both be of the custom payment method, or neither");
        checkArgument(isCustomOffer == PaymentMethod.CUSTOM_ID.equals(peerPaymentAccountPayload.getPaymentMethodId()),
                "Payment method id of peer's payment account does not match the offer");
        if (!isCustomOffer) return;
        CustomPaymentMethodAccountPayload payload = (CustomPaymentMethodAccountPayload) peerPaymentAccountPayload;
        checkArgument(isSameName(offer.getCustomPaymentMethodName(), payload.getPaymentMethodName()),
                "Peer's payment account is not for the custom payment method of the offer");
        checkArgument(isValidAccountData(payload.getAccountData()),
                "Account data of peer's payment account is not valid");
    }

    // Keeps letters, numbers and marks, without case. Symbols are dropped first, since NFKC turns some into letters.
    // Java has no case folding, so case is removed per character, by upper case of the text, then per character again.
    static String toComparableName(String name) {
        String normalized = Normalizer.normalize(keepLettersNumbersAndMarks(name), Normalizer.Form.NFKC);
        String withoutCase = toLowerCaseOfEachCharacter(
                toLowerCaseOfEachCharacter(normalized).toUpperCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder();
        keepLettersNumbersAndMarks(Normalizer.normalize(withoutCase, Normalizer.Form.NFKC)).codePoints()
                .map(CustomPaymentMethodUtil::toDigitOfItsValue)
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static String keepLettersNumbersAndMarks(String text) {
        StringBuilder sb = new StringBuilder();
        text.codePoints()
                .filter(codePoint -> isLetterOrNumber(codePoint) || isMark(codePoint))
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    // decimal digits of all scripts with the same value are the same digit
    private static int toDigitOfItsValue(int codePoint) {
        return Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER ?
                '0' + Character.digit(codePoint, 10) :
                codePoint;
    }

    private static String toLowerCaseOfEachCharacter(String text) {
        StringBuilder sb = new StringBuilder();
        text.codePoints()
                .map(Character::toLowerCase)
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static Stream<String> getNamesOfPaymentMethod(String paymentMethodId) {
        return Stream.concat(Stream.of(paymentMethodId),
                Stream.of(Res.getResourceBundle(), Res.getDefaultResourceBundle())
                        .flatMap(resourceBundle -> Stream.of(paymentMethodId, paymentMethodId + "_SHORT")
                                .filter(resourceBundle::containsKey)
                                .map(resourceBundle::getString)));
    }

    private static boolean hasLetterOrNumber(String text) {
        return text.codePoints().anyMatch(CustomPaymentMethodUtil::isLetterOrNumber);
    }

    // numerals of some scripts are not decimal digits
    private static boolean isLetterOrNumber(int codePoint) {
        if (Character.isLetter(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.DECIMAL_DIGIT_NUMBER ||
                type == Character.LETTER_NUMBER ||
                type == Character.OTHER_NUMBER;
    }

    private static boolean isMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK ||
                type == Character.COMBINING_SPACING_MARK ||
                type == Character.ENCLOSING_MARK;
    }

    // counts marks in the decomposed form, where a letter with an accent is the letter and a mark
    private static boolean hasTooManyMarksInARow(String text) {
        int marksInARow = 0;
        for (int codePoint : Normalizer.normalize(text, Normalizer.Form.NFKD).codePoints().toArray()) {
            if (isMarkAboveOrBelow(codePoint)) {
                marksInARow++;
                if (marksInARow > MAX_MARKS_IN_A_ROW) {
                    return true;
                }
            } else if (codePoint != ZERO_WIDTH_NON_JOINER && codePoint != ZERO_WIDTH_JOINER) {
                // a joiner is not shown, so marks after it are drawn on the same character
                marksInARow = 0;
            }
        }
        return false;
    }

    private static boolean isMarkAboveOrBelow(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    private static boolean hasOnlyAllowedCharacters(String text, boolean allowLineBreaks) {
        return text.codePoints().allMatch(codePoint -> isAllowedCharacter(codePoint, allowLineBreaks));
    }

    private static String removeNotAllowedCharacters(String text, boolean allowLineBreaks) {
        StringBuilder sb = new StringBuilder();
        text.codePoints()
                .filter(codePoint -> isAllowedCharacter(codePoint, allowLineBreaks))
                .forEach(sb::appendCodePoint);
        return sb.toString();
    }

    // Control and format characters can break lines, hide text or reorder it. Characters unknown to the Java runtime
    // are refused since a newer runtime could compare them differently.
    private static boolean isAllowedCharacter(int codePoint, boolean allowLineBreaks) {
        if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
            return allowLineBreaks;
        }
        if (codePoint == ZERO_WIDTH_NON_JOINER || codePoint == ZERO_WIDTH_JOINER) {
            return true;
        }
        if (isNotShown(codePoint)) {
            return false;
        }
        switch (Character.getType(codePoint)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.PRIVATE_USE:
            case Character.SURROGATE:
            case Character.UNASSIGNED:
                return false;
            default:
                return true;
        }
    }

    // letters and marks which are not shown but would be compared
    private static boolean isNotShown(int codePoint) {
        return codePoint == 0x034F ||                               // combining grapheme joiner
                codePoint == 0x115F || codePoint == 0x1160 ||       // Hangul fillers
                codePoint == 0x17B4 || codePoint == 0x17B5 ||       // Khmer inherent vowels
                (codePoint >= 0x180B && codePoint <= 0x180F) ||     // Mongolian variation selectors
                codePoint == 0x3164 || codePoint == 0xFFA0 ||       // Hangul fillers
                (codePoint >= 0xFE00 && codePoint <= 0xFE0F) ||     // variation selectors
                (codePoint >= 0xE0100 && codePoint <= 0xE01EF);     // variation selectors supplement
    }
}
