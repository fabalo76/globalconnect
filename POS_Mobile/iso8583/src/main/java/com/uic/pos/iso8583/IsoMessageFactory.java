package com.uic.pos.iso8583;

import com.uic.pos.iso8583.exception.Iso8583ParseException;
import com.uic.pos.iso8583.util.IsoBitUtils;
import com.uic.pos.iso8583.util.IsoHexUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds and parses ISO 8583 messages based on a configured set of field parsers.
 *
 * @author UIC
 */
public final class IsoMessageFactory {
    private final Map<Integer, IsoFieldParser> parserMap = new HashMap<>();
    private boolean dynamicBitmapFromConfig;

    /**
     * Registers the parser associated with a specific field index.
     *
     * @param fieldIndex the ISO 8583 field number.
     * @param parser     the parser that can encode/decode the field value.
     */
    public void registerParser(int fieldIndex, IsoFieldParser parser) {
        parserMap.put(fieldIndex, parser);
    }

    /**
     * Returns the parser map as an unmodifiable view.
     *
     * @return the configured field parsers.
     */
    public Map<Integer, IsoFieldParser> getParserMap() {
        return Collections.unmodifiableMap(parserMap);
    }

    /**
     * Returns the parser registered for the supplied field index.
     *
     * @param fieldIndex the ISO 8583 field number.
     * @return the parser or {@code null} when no configuration is available.
     */
    public IsoFieldParser getParser(int fieldIndex) {
        return parserMap.get(fieldIndex);
    }

    /**
     * Creates a new message instance that is bound to this factory.
     *
     * @return a mutable ISO 8583 message.
     */
    public IsoMessage newMessage() {
        IsoMessage message = new IsoMessage(this);
        message.setDynamicBitmap(dynamicBitmapFromConfig);
        return message;
    }

    /**
     * Parses a raw buffer into an ISO 8583 message.
     *
     * @param buffer       the raw message bytes.
     * @param headerLength the number of bytes that belong to the TPDU/header before the message type.
     * @return the parsed message.
     * @throws Iso8583ParseException if the buffer does not match the configured structure.
     */
    public IsoMessage parse(byte[] buffer, int headerLength) throws Iso8583ParseException {
        if (buffer == null) {
            throw new Iso8583ParseException("Buffer cannot be null");
        }
        if (headerLength < 0 || headerLength > buffer.length) {
            throw new Iso8583ParseException("Header length is outside of the buffer");
        }

        IsoMessage message = newMessage();
        if (headerLength > 0) {
            byte[] header = Arrays.copyOfRange(buffer, 0, headerLength);
            message.setHeader(IsoHexUtils.encodeHex(header, 0, header.length));
        }

        int offset = headerLength;
        IsoFieldParser typeParser = parserMap.get(0);
        if (typeParser != null) {
            IsoFieldValue messageType = typeParser.parse(0, buffer, offset);
            message.setMessageType(messageType.getValue());
            message.setFieldInternal(0, messageType);
            offset += messageType.getParseOffset();
        }

        IsoFieldParser bitmapParser = parserMap.get(1);
        if (bitmapParser == null) {
            throw new Iso8583ParseException("Bitmap field (1) is not configured");
        }
        IsoFieldValue bitmapValue = bitmapParser.parse(1, buffer, offset);
        String bitmapHex = bitmapValue.getValue();
        byte[] bitmapBytes = IsoHexUtils.decodeHex(bitmapHex);
        int consumed = bitmapValue.getParseOffset();

        if (dynamicBitmapFromConfig && bitmapBytes.length > 8 && (bitmapBytes[0] & 0x80) == 0) {
            // Secondary bitmap disabled but configuration expects more data. Shrink the bitmap to eight bytes.
            bitmapBytes = Arrays.copyOf(bitmapBytes, 8);
            bitmapHex = IsoHexUtils.encodeHex(bitmapBytes, 0, bitmapBytes.length);
            consumed = bitmapParser.getLengthIndicatorSize() + 8;
        }

        bitmapValue.setValue(bitmapHex);
        bitmapValue.setParseOffset(consumed);
        message.setFieldInternal(1, bitmapValue);
        offset += consumed;

        int maxField = bitmapBytes.length * 8;
        for (int bit = 1; bit < maxField; bit++) {
            if (!IsoBitUtils.getBit(bitmapBytes, bit)) {
                continue;
            }
            int fieldIndex = bit + 1;
            IsoFieldParser parser = parserMap.get(fieldIndex);
            if (parser == null) {
                throw new Iso8583ParseException("Field " + fieldIndex + " is not configured");
            }
            IsoFieldValue value = parser.parse(fieldIndex, buffer, offset);
            message.setFieldInternal(fieldIndex, value);
            offset += value.getParseOffset();
        }
        return message;
    }

    /**
     * Enables or disables dynamic bitmap handling. When enabled the primary bitmap length is
     * automatically reduced to eight bytes when no secondary bitmap is required.
     *
     * @param enabled {@code true} to enable dynamic behaviour.
     */
    public void setDynamicBitmapFromConfig(boolean enabled) {
        this.dynamicBitmapFromConfig = enabled;
    }

    /**
     * Indicates whether dynamic bitmap handling is enabled.
     *
     * @return {@code true} if the behaviour is enabled.
     */
    public boolean isDynamicBitmapFromConfig() {
        return dynamicBitmapFromConfig;
    }
}
