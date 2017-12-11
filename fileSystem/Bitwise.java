package fileSystem;

/**
 * Manage bitwise operations in a byte or array of bytes.
 *
 * Unit tests are in {@see TestBitwise}. See TestBitwise.java.
 */
public class Bitwise {
	
    private static final int bitmasks[] = {1, 2, 4, 8, 16, 32, 64, 128};

    /**
     * Check to see if bit i is set in byte. Returns true if it is
     * set, false otherwise.
     */
    public static boolean isset(int i, byte b) {
    	//FIXME!!!
    	return (b & bitmasks[i]) == bitmasks[i];//use bitwise AND operation to check if bit i is set
    }

    /**
     * Check to see if bit i is set in array of bytes. Returns true if
     * it is set, false otherwise.
     */
    public static boolean isset(int i, byte bytes[]) {
    	//FIXME!!!
    	int size = bytes.length - 1;
    	if ((bytes[size - (i/8)] & bitmasks[i%8]) == bitmasks[i%8]) {
    		//calculates index in array bytes that corresponds to i, then uses bitwise AND operation to check if
    		//its set to bitmask i%8 
			return true;
		}
    	
    	return false;
    }

    /**
     * Set bit i in byte and return the new byte.
     */
    public static byte set(int i, byte b) {
    	//FIXME!!!
    	return (byte) (b | bitmasks[i]);
    	//sets bit i in byte by using bitwise OR operation to change it
    }

    /**
     * Set bit i in array of bytes.
     */
    public static void set(int i, byte bytes[]) {
    	//FIXME!!!
    	int size = bytes.length - 1;
    	bytes[size - (i/8)] = (byte) (bytes[size - (i/8)] | bitmasks[i%8]);
    	//calculates index in array bytes that corresponds to i, then uses bitwise OR to change it and set it
    }

    /**
     * Clear bit i in byte and return the new byte.
     */
    public static byte clear(int i, byte b) {
    	//FIXME!!!
    	return (byte) (b & ~bitmasks[i]);
    	//clears bit i by using bitwise AND operation of inverted bit mask i
    }

    /**
     * Clear bit i in array of bytes and return true if the bit was 1
     * before clearing, false otherwise.
     */
    public static boolean clear(int i, byte bytes[]) {
    	//FIXME!!!
    	int size = bytes.length - 1;
    	boolean check = isset(i, bytes);
    	bytes[size - (i/8)] = (byte) (bytes[size - (i/8)] & ~bitmasks[i%8]);
    	//calculates index in array bytes that corresponds to i, then uses bitwise AND operation of inverted bitmask i%8
    	return check;
    }

    /**
     * Clear every bit in array of bytes.
     *
     * There is no clearAll for a single byte, you can just get a new
     * byte for that.
     */
    public static void clearAll(byte bytes[]) {
        for(int i = 0; i < bytes.length; ++i) {
            bytes[i] = 0;
        }
    }

    /**
     * Convert byte to a string of bits. Each bit is represented as
     * "0" if it is clear, "1" if it is set.
     */
    public static String toString(byte b) {
    	//FIXME!!!
    	String s = "";
        for(int i = 7; i >= 0; --i) {
        	s += isset(i, b) ? "1" : "0";
        	
        }//uses isset on each bit in byte b to check if its 0 or 1, then adds whichever it its to string s
        return s;
    }

    /**
     * Convert array of bytes to string of bits (each byte converted
     * to a string by calling {@link #byteToString(byte b)}, every
     * byte separated by sep, every "every" bytes separated by lsep.
     */
    public static String toString(byte bytes[], String sep,
                                  String lsep, int every) {
        String s = "";
        for(int i = bytes.length * 8 - 1; i >= 0; --i) {
        	s += isset(i, bytes) ? "1" : "0";
        	if(i > 0)
                if(every > 0 && i % (8 * every) == 0)
                    s += lsep;
                else if(i % 8 == 0)
                    s += sep;
        }
        return s;
    }

    /**
     * Convert array of bytes to string of bits, each byte separated
     * by sep. See {@link #byteToString(byte bytes[], String sep)}.
     */
    public static String toString(byte bytes[], String sep) {
        return toString(bytes, sep, null, 0);
    }

    /**
     * Convert array of bytes to string of bits, each byte separated
     * by a comma, and every 8 bytes separated by a newline. See
     * {@link #byteToString(byte bytes[], String sep)}.
     */
    public static String toString(byte bytes[]) {
        return toString(bytes, ",", "\n", 8);
    }
}