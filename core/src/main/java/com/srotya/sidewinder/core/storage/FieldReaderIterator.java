/**
 * Copyright Ambud Sharma
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 		http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.srotya.sidewinder.core.storage;

import java.io.IOException;
import java.util.List;

import com.srotya.sidewinder.core.storage.compression.Reader;

public class FieldReaderIterator {

	private int idx;
	private List<Reader> readers;

	public FieldReaderIterator(List<Reader> readers) {
		this.readers = readers;
	}

	public long next() throws IOException {
		try {
			return readers.get(idx).read();
		} catch (RejectException e) {
			if (idx < readers.size() - 1) {
				idx++;
				return next();
			} else {
				throw e;
			}
		}
	}

}
