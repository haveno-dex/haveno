/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.network;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps an underlying stream and allows only a fixed number of bytes to be
 * read from it before it returns EOF. Used to feed protobuf's CodedInputStream
 * exactly the byte budget advertised in the varint length prefix, so a remote
 * peer cannot force protobuf to allocate beyond the declared envelope size.
 */
final class BoundedInputStream extends FilterInputStream {

    private long remaining;

    BoundedInputStream(InputStream in, long maxBytes) {
        super(in);
        this.remaining = maxBytes;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) return -1;
        int b = in.read();
        if (b >= 0) remaining--;
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (remaining <= 0) return -1;
        int toRead = (int) Math.min(len, remaining);
        int read = in.read(buf, off, toRead);
        if (read > 0) remaining -= read;
        return read;
    }

    @Override
    public long skip(long n) throws IOException {
        long toSkip = Math.min(n, remaining);
        long skipped = in.skip(toSkip);
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(in.available(), remaining);
    }
}
