package org.exist.storage;

import java.math.BigDecimal;
import org.exist.EXistException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.exist.storage.btree.Value;
import org.exist.util.ByteConversion;
import org.exist.xquery.value.DecimalValue;
import org.junit.Test;


public class ValueIndexFactoryTest {
	
    @Test
    public void negativeNumbersComparison() {
        byte[] data1 = encode(-8.612328);

        long v1 = ByteConversion.byteToLong(data1, 0);

        byte[] data2 = encode(1.0);

        long v2 = ByteConversion.byteToLong(data2, 0);

        assertTrue("v1= " + v1 + " v2 = " + v2, v1 < v2);
        assertEquals("v1 < v2", -1, (new Value(data1).compareTo(new Value(data2))));

        print(data1);
        print(data2);
    }

    @Test
    public void roundTripDecimal() throws EXistException {
        BigDecimal dec = new BigDecimal("123456789123456789123456789123456789.123456789123456789123456789");

        byte data[] = ValueIndexFactory.serialize(new DecimalValue(dec), 0);

        Indexable value = ValueIndexFactory.deserialize(data, 0, data.length);
        assertTrue(value instanceof DecimalValue);

        assertEquals(dec, ((DecimalValue)value).getValue());
    }
	
    private byte[] encode(double number) {
        final long bits = Double.doubleToLongBits(number);
        byte[] data = new byte[8];
        ByteConversion.longToByte(bits, data, 0);
        return data;
    }

    private static void print(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            System.out.print(Byte.toString(data[i]) + " ");
        }
        System.out.println();
    }
}