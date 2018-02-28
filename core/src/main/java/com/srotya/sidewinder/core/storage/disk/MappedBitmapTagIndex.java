/**
 * Copyright 2017 Ambud Sharma
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
package com.srotya.sidewinder.core.storage.disk;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Logger;

import org.roaringbitmap.buffer.MutableRoaringBitmap;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.srotya.sidewinder.core.filters.Filter;
import com.srotya.sidewinder.core.filters.tags.TagFilter;
import com.srotya.sidewinder.core.monitoring.MetricsRegistryService;
import com.srotya.sidewinder.core.storage.Measurement;
import com.srotya.sidewinder.core.storage.RejectException;
import com.srotya.sidewinder.core.storage.SeriesFieldMap;
import com.srotya.sidewinder.core.storage.TagIndex;

/**
 * Tag hash lookup table + Tag inverted index
 * 
 * @author ambud
 */
public class MappedBitmapTagIndex implements TagIndex {

	private static final Logger logger = Logger.getLogger(MappedBitmapTagIndex.class.getName());
	private static final int INCREMENT_SIZE = 1024 * 1024 * 1;
	private Map<String, SortedMap<String, MutableRoaringBitmap>> rowkeyIndex;
	private String indexPath;
	private File revIndex;
	private Counter metricIndexRow;
	private boolean enableMetrics;
	private RandomAccessFile revRaf;
	private MappedByteBuffer rev;
	private PersistentMeasurement measurement;

	public MappedBitmapTagIndex(String indexDir, String measurementName, PersistentMeasurement measurement)
			throws IOException {
		this.measurement = measurement;
		this.indexPath = indexDir + "/" + measurementName;
		rowkeyIndex = new ConcurrentHashMap<>();
		revIndex = new File(indexPath + ".rev");
		MetricsRegistryService instance = MetricsRegistryService.getInstance();
		if (instance != null) {
			MetricRegistry registry = instance.getInstance("requests");
			metricIndexRow = registry.counter("index-row");
			enableMetrics = true;
		}
		loadTagIndex();
	}

	protected void loadTagIndex() throws IOException {
		if (!revIndex.exists()) {
			revRaf = new RandomAccessFile(revIndex, "rwd");
			rev = revRaf.getChannel().map(MapMode.READ_WRITE, 0, INCREMENT_SIZE);
			rev.putInt(0);
			logger.fine("Tag index is missing; initializing new index");
		} else {
			revRaf = new RandomAccessFile(revIndex, "rwd");
			logger.info("Tag index is present; loading:" + revIndex.getAbsolutePath());
			rev = revRaf.getChannel().map(MapMode.READ_WRITE, 0, revIndex.length());

			// load reverse lookup
			int offsetLimit = rev.getInt();
			while (rev.position() < offsetLimit) {
				int length = rev.getInt();
				byte[] b = new byte[length];
				rev.get(b);
				String r = new String(b);
				String[] split = r.split(" ");
				String tagKey = split[0];
				String tagValue = split[1];
				SortedMap<String, MutableRoaringBitmap> map = rowkeyIndex.get(tagKey);
				if (map == null) {
					map = new ConcurrentSkipListMap<>();
					rowkeyIndex.put(tagKey, map);
				}
				MutableRoaringBitmap set = map.get(tagValue);
				if (set == null) {
					set = new MutableRoaringBitmap();
					map.put(tagValue, set);
				}
				String rowKeyIndex = split[2];
				set.add(Integer.parseInt(rowKeyIndex));
			}
		}
	}

	@Override
	public String mapTagKey(String tagKey) throws IOException {
		return tagKey;
	}

	@Override
	public String getTagKeyMapping(String hexString) {
		return hexString;
	}

	@Override
	public Set<String> getTags() {
		Set<String> set = new HashSet<>();
		for (String key : rowkeyIndex.keySet()) {
			Map<String, MutableRoaringBitmap> map = rowkeyIndex.get(key);
			for (String value : map.keySet()) {
				set.add(key + Measurement.TAG_KV_SEPARATOR + value);
			}
		}
		return set;
	}

	@Override
	public Collection<String> searchRowKeysForTag(String tagKey, String tagValue) {
		List<String> rowKeys = new ArrayList<>();
		Map<String, MutableRoaringBitmap> map = rowkeyIndex.get(tagKey);
		if (map != null) {
			MutableRoaringBitmap value = map.get(tagValue);
			if (value != null) {
				List<SeriesFieldMap> ref = measurement.getSeriesListAsList();
				for (Iterator<Integer> iterator = value.iterator(); iterator.hasNext();) {
					Integer idx = iterator.next();
					rowKeys.add(ref.get(idx).getSeriesId());
				}
			}
		}
		return rowKeys;
	}

	public Collection<String> evalFilterForTags(TagFilter filterTree) {
		
		return null;
	}

	@Override
	public void close() throws IOException {
		rev.force();
		revRaf.close();
	}

	public MutableRoaringBitmap getBitMapForTag(String tagKey, String tagValue) {
		return rowkeyIndex.get(tagKey).get(tagValue);
	}

	@Override
	public void index(String tagKey, String tagValue, int rowIndex) throws IOException {
		SortedMap<String, MutableRoaringBitmap> tagValueMap = rowkeyIndex.get(tagKey);
		if (tagValueMap == null) {
			synchronized (rowkeyIndex) {
				if ((tagValueMap = rowkeyIndex.get(tagKey)) == null) {
					tagValueMap = new ConcurrentSkipListMap<>();
					rowkeyIndex.put(tagKey, tagValueMap);
				}
			}
		}

		MutableRoaringBitmap rowKeySet = tagValueMap.get(tagValue);
		if (rowKeySet == null) {
			synchronized (tagValueMap) {
				if ((rowKeySet = tagValueMap.get(tagValue)) == null) {
					rowKeySet = new MutableRoaringBitmap();
					tagValueMap.put(tagValue, rowKeySet);
				}
			}
		}

		if (!rowKeySet.contains(rowIndex)) {
			boolean add = rowKeySet.checkedAdd(rowIndex);
			if (add) {
				if (enableMetrics) {
					metricIndexRow.inc();
				}
				synchronized (rowkeyIndex) {
					byte[] str = (tagKey + " " + tagValue + " " + rowIndex).getBytes();
					if (rev.remaining() < str.length + Integer.BYTES) {
						// resize buffer
						int temp = rev.position();
						rev = revRaf.getChannel().map(MapMode.READ_WRITE, 0, rev.capacity() + INCREMENT_SIZE);
						rev.position(temp);
					}
					rev.putInt(str.length);
					rev.put(str);
					rev.putInt(0, rev.position());
				}
			}
		}
	}

	@Override
	public int getSize() {
		int total = 0;
		for (Entry<String, SortedMap<String, MutableRoaringBitmap>> entry : rowkeyIndex.entrySet()) {
			for (Entry<String, MutableRoaringBitmap> entry2 : entry.getValue().entrySet()) {
				total += entry2.getValue().getSizeInBytes() + entry.getKey().length();
			}
		}
		return total;
	}

	@Override
	public String mapTagValue(String tagValue) throws IOException {
		return tagValue;
	}

	@Override
	public String getTagValueMapping(String tagValue) throws IOException {
		return tagValue;
	}

	@Override
	public void index(String tag, String value, String rowKey) throws IOException {
		// not implemented
	}

}