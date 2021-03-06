/** 
 * 	Copyright 2016-2018 Michael Burman and/or other contributors.
*
*   Licensed under the Apache License, Version 2.0 (the "License");
*   you may not use this file except in compliance with the License.
*   You may obtain a copy of the License at
*
*       http://www.apache.org/licenses/LICENSE-2.0
*
*   Unless required by applicable law or agreed to in writing, software
*   distributed under the License is distributed on an "AS IS" BASIS,
*   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*   See the License for the specific language governing permissions and
*   limitations under the License.
**/  
package com.srotya.sidewinder.core.storage.compression.gorilla;

import com.srotya.sidewinder.core.storage.RejectException;
import com.srotya.sidewinder.core.storage.compression.Reader;

/**
 * Decompresses a compressed stream created by the GorillaCompressor.
 *
 * @author Michael Burman
 */
public class GorillaTimestampDecompressor {
    
	private long storedTimestamp = 0;
    private long storedDelta = 0;

    private long blockTimestamp = 0;
    private boolean endOfStream = false;

    private BitInput in;

    public GorillaTimestampDecompressor(BitInput input) {
        this(input, new LastValuePredictor());
    }

    public GorillaTimestampDecompressor(BitInput input, Predictor predictor) {
        in = input;
        readHeader();
    }

    private void readHeader() {
        blockTimestamp = in.getLong(64);
    }
    
    public long read() throws RejectException {
		next();
		if (endOfStream) {
			throw Reader.EOS_EXCEPTION;
		}
		return storedTimestamp;
	}

    private void next() {
        // TODO I could implement a non-streaming solution also.. is there ever a need for streaming solution?

        if(storedTimestamp == 0) {
            first();
            return;
        }

        nextTimestamp();
    }

    private void first() {
        // First item to read
        storedDelta = in.getLong(Compressor.FIRST_DELTA_BITS);
        if(storedDelta == (1<<27) - 1) {
            endOfStream = true;
            return;
        }
//        storedVal = in.getLong(64);
        storedTimestamp = blockTimestamp + storedDelta;
    }

    private void nextTimestamp() {
        // Next, read timestamp
        int readInstruction = in.nextClearBit(4);
        long deltaDelta;

        switch(readInstruction) {
            case 0x00:
                storedTimestamp = storedDelta + storedTimestamp;
                return;
            case 0x02:
                deltaDelta = in.getLong(7);
                break;
            case 0x06:
                deltaDelta = in.getLong(9);
                break;
            case 0x0e:
                deltaDelta = in.getLong(12);
                break;
            case 0x0F:
                deltaDelta = in.getLong(32);
                // For storage save.. if this is the last available word, check if remaining bits are all 1
                if ((int) deltaDelta == 0xFFFFFFFF) {
                    // End of stream
                    endOfStream = true;
                    return;
                }
                break;
            default:
                return;
        }

        deltaDelta++;
        deltaDelta = decodeZigZag32((int) deltaDelta);
        storedDelta = storedDelta + deltaDelta;

        storedTimestamp = storedDelta + storedTimestamp;
    }

    // START: From protobuf

    /**
     * Decode a ZigZag-encoded 32-bit value. ZigZag encodes signed integers into values that can be
     * efficiently encoded with varint. (Otherwise, negative values must be sign-extended to 64 bits
     * to be varint encoded, thus always taking 10 bytes on the wire.)
     *
     * @param n An unsigned 32-bit integer, stored in a signed int because Java has no explicit
     *     unsigned support.
     * @return A signed 32-bit integer.
     */
    public static int decodeZigZag32(final int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    // END: From protobuf

}