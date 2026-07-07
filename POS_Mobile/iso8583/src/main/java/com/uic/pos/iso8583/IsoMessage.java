package com.uic.pos.iso8583;

import com.uic.pos.iso8583.exception.Iso8583Exception;
import com.uic.pos.iso8583.util.IsoBitUtils;
import com.uic.pos.iso8583.util.IsoByteUtils;
import com.uic.pos.iso8583.util.IsoHexUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Represents a mutable ISO 8583 message that can be populated using the field definitions provided
 * by {@link IsoMessageFactory}.
 *
 * @author UIC
 */
public final class IsoMessage {
    private final IsoFieldValue[] fields = new IsoFieldValue[129];
    private final IsoMessageFactory factory;
    private String headerHex;
    private String messageType;
    private boolean dynamicBitmap;

    IsoMessage(IsoMessageFactory factory) {
        this.factory = factory;
    }

    /**
     * Returns the hexadecimal representation of the transport header (TPDU).
     *
     * @return the header as a hex string or {@code null} when unset.
     */
    public String getHeader() {
        return headerHex;
    }

    /**
     * Updates the hexadecimal representation of the transport header (TPDU).
     *
     * @param headerHex the header represented as a hex string.
     */
    public void setHeader(String headerHex) {
        this.headerHex = headerHex;
    }

    /**
     * Returns the ISO 8583 message type identifier (field 0).
     *
     * @return the message type or {@code null} when unset.
     */
    public String getMessageType() {
        return messageType;
    }

    /**
     * Sets the ISO 8583 message type identifier (field 0).
     *
     * @param messageType the message type represented as a four digit string.
     */
    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    /**
     * Assigns a textual value to the supplied field index.
     *
     * @param fieldIndex the ISO 8583 field number.
     * @param value      the textual representation of the field contents.
     * @return this message for chaining.
     * @throws Iso8583Exception if the field is not configured.
     */
    public IsoMessage setFieldValue(int fieldIndex, String value) throws Iso8583Exception {
        ensureFieldIndex(fieldIndex);
        IsoFieldParser parser = factory.getParser(fieldIndex);
        if (parser == null) {
            throw new Iso8583Exception("Field " + fieldIndex + " is not configured");
        }
        fields[fieldIndex] = (value == null || value.isEmpty()) ? null : new IsoFieldValue(parser, value);
        return this;
    }

    /**
     * Indicates whether a value has been assigned to the supplied field.
     *
     * @param fieldIndex the ISO 8583 field number.
     * @return {@code true} when the field contains a value.
     */
    public boolean hasField(int fieldIndex) {
        ensureFieldIndex(fieldIndex);
        return fields[fieldIndex] != null;
    }

    /**
     * Returns the value assigned to the supplied field.
     *
     * @param fieldIndex the ISO 8583 field number.
     * @return the textual representation of the field contents or {@code null} when absent.
     */
    public String getFieldValue(int fieldIndex) {
        ensureFieldIndex(fieldIndex);
        IsoFieldValue value = fields[fieldIndex];
        return value == null ? null : value.getValue();
    }

    /**
     * Serialises the message using the configured field definitions.
     *
     * @return the ISO 8583 message body without length prefix or header.
     * @throws Iso8583Exception if the message cannot be encoded.
     */
    public byte[] toByteArray() throws Iso8583Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (messageType != null) {
                setFieldValue(0, messageType);
            }
            setFieldValue(1, buildBitmap());
            for (IsoFieldValue value : fields) {
                if (value != null) {
                    value.write(output);
                }
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new Iso8583Exception("Unable to encode ISO 8583 message", e);
        }
    }

    /**
     * Serialises the message and prefixes it with the requested length header and transport header.
     *
     * @param lengthBytes the number of bytes used for the length prefix (0-4).
     * @param lengthType  the encoding to use for the length prefix.
     * @return the fully framed ISO 8583 message.
     * @throws Iso8583Exception if the message cannot be encoded.
     */
    public byte[] toByteArray(int lengthBytes, IsoLengthType lengthType) throws Iso8583Exception {
        if (lengthBytes < 0 || lengthBytes > 4) {
            throw new Iso8583Exception("Length prefix must use between 0 and 4 bytes");
        }
        byte[] body = toByteArray();
        byte[] header = headerHex == null ? new byte[0] : IsoHexUtils.decodeHex(headerHex);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            if (lengthBytes > 0) {
                int totalLength = body.length + header.length;
                output.write(IsoByteUtils.intToBytes(totalLength, lengthType, lengthBytes));
            }
            output.write(header);
            output.write(body);
            return output.toByteArray();
        } catch (IOException e) {
            throw new Iso8583Exception("Unable to encode ISO 8583 message", e);
        }
    }

    /**
     * Returns the hexadecimal representation of the bitmap stored in field 1.
     *
     * @return the bitmap or {@code null} when it has not been generated.
     */
    public String getBitmap() {
        return getFieldValue(1);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            IsoFieldValue value = fields[i];
            if (value == null) {
                continue;
            }
            String output = value.getValue();
            if (i == 2 || i == 35 || i == 36) {
                output = mask(output, 6, 4, '*');
            } else if (i == 14) {
                output = "****";
            }
            builder.append("field").append(i).append(':').append(output).append('\n');
        }
        return builder.toString();
    }

    void setFieldInternal(int fieldIndex, IsoFieldValue value) {
        ensureFieldIndex(fieldIndex);
        fields[fieldIndex] = value;
    }

    void setDynamicBitmap(boolean dynamicBitmap) {
        this.dynamicBitmap = dynamicBitmap;
    }

    boolean isDynamicBitmap() {
        return dynamicBitmap;
    }

    private void ensureFieldIndex(int fieldIndex) {
        if (fieldIndex < 0 || fieldIndex >= fields.length) {
            throw new IllegalArgumentException("Field index must be between 0 and 128 inclusive");
        }
    }

    private String buildBitmap() throws Iso8583Exception {
        IsoFieldParser bitmapParser = factory.getParser(1);
        if (bitmapParser == null) {
            throw new Iso8583Exception("Bitmap field (1) is not configured");
        }
        int configuredBytes = bitmapParser.getFieldType() == IsoFieldType.ALPHA
                ? bitmapParser.getMaxLength() / 2
                : bitmapParser.getMaxLength();
        if (configuredBytes <= 0) {
            throw new Iso8583Exception("Bitmap configuration length must be greater than zero");
        }

        int requiredBytes = configuredBytes;
        if (dynamicBitmap) {
            boolean requiresSecondary = false;
            for (int i = 65; i <= 128; i++) {
                if (fields[i] != null) {
                    requiresSecondary = true;
                    break;
                }
            }
            if (requiresSecondary) {
                if (configuredBytes < 16) {
                    throw new Iso8583Exception("Secondary bitmap required but configuration allows only "
                            + configuredBytes + " bytes");
                }
                requiredBytes = Math.min(16, configuredBytes);
            } else {
                requiredBytes = Math.min(8, configuredBytes);
            }
        }

        byte[] bitmap = new byte[requiredBytes];
        Arrays.fill(bitmap, (byte) 0);
        if (requiredBytes > 8) {
            IsoBitUtils.setBit(bitmap, 0, true);
        }
        for (int bit = 1; bit < requiredBytes * 8; bit++) {
            int fieldIndex = bit + 1;
            if (fieldIndex >= fields.length) {
                break;
            }
            IsoBitUtils.setBit(bitmap, bit, fields[fieldIndex] != null);
        }
        return IsoHexUtils.encodeHex(bitmap, 0, bitmap.length);
    }

    private String mask(String value, int prefix, int suffix, char maskChar) {
        if (value == null || value.length() <= prefix + suffix) {
            return value;
        }
        StringBuilder builder = new StringBuilder(value.length());
        builder.append(value, 0, prefix);
        for (int i = prefix; i < value.length() - suffix; i++) {
            builder.append(maskChar);
        }
        builder.append(value, value.length() - suffix, value.length());
        return builder.toString();
    }
}
