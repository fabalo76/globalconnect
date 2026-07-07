package com.uic.pos.iso8583.config;

import com.uic.pos.iso8583.IsoAlign;
import com.uic.pos.iso8583.IsoFieldParser;
import com.uic.pos.iso8583.IsoFieldType;
import com.uic.pos.iso8583.IsoLengthType;
import com.uic.pos.iso8583.IsoMessageFactory;
import com.uic.pos.iso8583.exception.Iso8583ParseException;
import com.uic.pos.iso8583.util.IsoHexUtils;
import com.uic.pos.iso8583.util.IsoPatternUtils;
import com.uic.pos.iso8583.util.IsoXmlUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Parses ISO 8583 XML configuration files and produces {@link IsoMessageFactory} instances.
 *
 * @author UIC
 */
public final class IsoConfigParser {
    private IsoConfigParser() {
    }

    /**
     * Creates a new {@link IsoMessageFactory} from the supplied XML file.
     *
     * @param file the configuration file.
     * @return an {@link IsoMessageFactory} configured according to the file contents.
     * @throws IOException            if the file cannot be read.
     * @throws Iso8583ParseException  if the XML structure is invalid.
     */
    public static IsoMessageFactory fromFile(File file) throws IOException, Iso8583ParseException {
        if (file == null) {
            throw new IllegalArgumentException("file cannot be null");
        }
        try (InputStream stream = new FileInputStream(file)) {
            return parse(stream);
        }
    }

    /**
     * Creates a new {@link IsoMessageFactory} from an input stream.
     *
     * @param stream the stream that provides the XML contents.
     * @return a configured {@link IsoMessageFactory} instance.
     * @throws Iso8583ParseException if the XML structure is invalid.
     */
    public static IsoMessageFactory fromStream(InputStream stream) throws Iso8583ParseException {
        if (stream == null) {
            throw new IllegalArgumentException("stream cannot be null");
        }
        try {
            return parse(stream);
        } catch (IOException e) {
            throw new Iso8583ParseException("Unable to read ISO 8583 configuration", e);
        }
    }

    private static IsoMessageFactory parse(InputStream stream) throws IOException, Iso8583ParseException {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document document = builder.parse(stream);
            Element root = document.getDocumentElement();
            Element[] fields = IsoXmlUtils.getElementsByName(root, "field");
            if (fields.length == 0) {
                throw new Iso8583ParseException("Configuration does not contain any field definitions");
            }
            IsoMessageFactory factory = new IsoMessageFactory();
            for (Element field : fields) {
                int index = parseIndex(IsoXmlUtils.getAttribute(field, "index"));
                IsoFieldType fieldType = parseType(IsoXmlUtils.getAttribute(field, "type"));
                IsoAlign align = parseAlign(IsoXmlUtils.getAttribute(field, "align"));
                int maxLength = parseMaxLength(IsoXmlUtils.getAttribute(field, "type"));
                IsoLengthType lengthType = parseLengthType(IsoXmlUtils.getAttribute(field, "lenType"));
                int lengthIndicator = Math.max(0, IsoPatternUtils.countDots(IsoXmlUtils.getAttribute(field, "type")) - 1);
                String paddingAttr = IsoXmlUtils.getAttribute(field, "padding");
                boolean hasPadding = !paddingAttr.isEmpty();
                byte padding = hasPadding ? parsePadding(paddingAttr) : 0;
                IsoFieldParser parser = IsoFieldParser.create(fieldType, align, maxLength, lengthType, lengthIndicator,
                        hasPadding, padding);
                factory.registerParser(index, parser);
            }
            if (factory.getParser(1) == null) {
                IsoFieldParser bitmapParser = IsoFieldParser.create(
                        IsoFieldType.HEX,
                        IsoAlign.LEFT,
                        8,
                        IsoLengthType.HEX,
                        0,
                        false,
                        (byte) 0);
                factory.registerParser(1, bitmapParser);
            }
            return factory;
        } catch (ParserConfigurationException | SAXException e) {
            throw new Iso8583ParseException("Unable to parse ISO 8583 configuration", e);
        }
    }

    private static int parseIndex(String value) throws Iso8583ParseException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new Iso8583ParseException("Field index is not numeric: " + value, e);
        }
    }

    private static IsoFieldType parseType(String value) throws Iso8583ParseException {
        String[] letters = IsoPatternUtils.extractLetters(value);
        if (letters == null || letters.length == 0) {
            throw new Iso8583ParseException("Field type is missing for value: " + value);
        }
        String type = letters[0].toUpperCase();
        switch (type) {
            case "N":
                return IsoFieldType.NUMERIC;
            case "H":
                return IsoFieldType.HEX;
            case "A":
                return IsoFieldType.ALPHA;
            default:
                throw new Iso8583ParseException("Unsupported field type: " + value);
        }
    }

    private static IsoAlign parseAlign(String value) {
        return IsoAlign.RIGHT.name().equalsIgnoreCase(value) ? IsoAlign.RIGHT : IsoAlign.LEFT;
    }

    private static IsoLengthType parseLengthType(String value) {
        if (value == null) {
            return IsoLengthType.BCD;
        }
        if ("ASC".equalsIgnoreCase(value)) {
            return IsoLengthType.ASC;
        }
        if ("HEX".equalsIgnoreCase(value)) {
            return IsoLengthType.HEX;
        }
        return IsoLengthType.BCD;
    }

    private static int parseMaxLength(String value) throws Iso8583ParseException {
        String[] numbers = IsoPatternUtils.extractNumbers(value);
        if (numbers == null || numbers.length == 0) {
            throw new Iso8583ParseException("Field type does not declare a length: " + value);
        }
        return Integer.parseInt(numbers[0]);
    }

    private static byte parsePadding(String value) throws Iso8583ParseException {
        if (value.length() != 2) {
            throw new Iso8583ParseException("Padding value must be two hexadecimal characters: " + value);
        }
        return IsoHexUtils.decodeHex(value)[0];
    }
}
