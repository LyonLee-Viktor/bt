/*
 * Copyright (c) 2016—2021 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.net.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public interface ByteBufferView {

    int position();

    ByteBufferView position(int newPosition);

    int limit();

    ByteBufferView limit(int newLimit);

    int capacity();

    boolean hasRemaining();

    int remaining();

    byte get();

    short getShort();

    int getInt();

    ByteBufferView get(byte[] dst);

    void transferTo(ByteBuffer buffer);

    /**
     * Transfer the contents of this byte buffer view to the file channel passed in. Does not change the position of the
     * file channel
     * @param fc the file channel
     * @param offset the offset in the file channel
     * @return the number of byte transferred to the filechannel
     * @throws IOException on failure to write to the file channel
     */
    int transferTo(FileChannel fc, long offset) throws IOException;

    ByteBufferView duplicate();
}
