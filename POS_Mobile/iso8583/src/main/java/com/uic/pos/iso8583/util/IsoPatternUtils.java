package com.uic.pos.iso8583.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regular expression helpers used when parsing ISO 8583 XML definitions.
 *
 * @author UIC
 */
public final class IsoPatternUtils {
    private IsoPatternUtils() {
    }

    /**
     * Extracts the numeric sections of a string.
     *
     * @param value the value to analyse.
     * @return an array of numeric substrings or {@code null} when no numbers are present.
     */
    public static String[] extractNumbers(String value) {
        if (value == null) {
            return null;
        }
        List<String> numbers = new ArrayList<>();
        Matcher matcher = Pattern.compile("[0-9]{1,}").matcher(value);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers.toArray(new String[0]);
    }

    /**
     * Extracts contiguous alphabetic characters from a string.
     *
     * @param value the value to analyse.
     * @return an array containing all alphabetic sequences or {@code null} when absent.
     */
    public static String[] extractLetters(String value) {
        if (value == null) {
            return null;
        }
        List<String> letters = new ArrayList<>();
        Matcher matcher = Pattern.compile("[a-zA-Z]+").matcher(value);
        while (matcher.find()) {
            letters.add(matcher.group());
        }
        return letters.toArray(new String[0]);
    }

    /**
     * Counts how many times a dot character ('.') appears in the supplied string.
     *
     * @param value the value to analyse.
     * @return the number of dots found.
     */
    public static int countDots(String value) {
        if (value == null) {
            return 0;
        }
        Matcher matcher = Pattern.compile("\\.").matcher(value);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
