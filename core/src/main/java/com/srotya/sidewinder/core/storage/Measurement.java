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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.codahale.metrics.Counter;
import com.srotya.sidewinder.core.filters.TagFilter;
import com.srotya.sidewinder.core.predicates.Predicate;
import com.srotya.sidewinder.core.rpc.Point;
import com.srotya.sidewinder.core.rpc.Tag;
import com.srotya.sidewinder.core.storage.compression.Writer;

/**
 * @author ambud
 */
public interface Measurement {

	public static final RejectException INDEX_REJECT = new RejectException("Invalid tag, rejecting index");
	public static final RejectException SEARCH_REJECT = new RejectException("Invalid tag, rejecting index search");
	public static final String TAG_KV_SEPARATOR = "=";
	public static final String SERIESID_SEPARATOR = "#";
	public static final String USE_QUERY_POOL = "use.query.pool";
	public static final String TAG_SEPARATOR = "^";
	public static final TagComparator TAG_COMPARATOR = new TagComparator();
	public static final Exception NOT_FOUND_EXCEPTION = null;

	public void configure(Map<String, String> conf, StorageEngine engine, int defaultTimeBucketSize, String dbName,
			String measurementName, String baseIndexDirectory, String dataDirectory, DBMetadata metadata,
			ScheduledExecutorService bgTaskPool) throws IOException;

	public Set<ByteString> getSeriesKeys();

	public default Series getSeriesFromKey(ByteString key) {
		Integer index = getSeriesMap().get(key);
		if (index == null) {
			return null;
		} else {
			return getSeriesList().get(index);
		}
	}

	public TagIndex getTagIndex();

	public void loadTimeseriesFromMeasurements() throws IOException;

	public void close() throws IOException;

	public default Series getOrCreateSeriesFieldMap(List<Tag> tags, boolean preSorted) throws IOException {
		if (!preSorted) {
			Collections.sort(tags, TAG_COMPARATOR);
		}
		ByteString seriesId = constructSeriesId(tags, getTagIndex());
		int index = 0;
		Series seriesFieldMap = getSeriesFromKey(seriesId);
		if (seriesFieldMap == null) {
			getLock().lock();
			try {
				if ((seriesFieldMap = getSeriesFromKey(seriesId)) == null) {
					index = getSeriesList().size();
					Measurement.indexRowKey(getTagIndex(), index, tags);
					seriesFieldMap = new Series(this, seriesId, index);
					getSeriesList().add(seriesFieldMap);
					getSeriesMap().put(seriesId, index);
					if (isEnableMetricsCapture()) {
						getMetricsTimeSeriesCounter().inc();
					}
					final ByteString tmp = seriesId;
					getLogger().fine(() -> "Created new series:" + tmp + "\t");
				} else {
					index = getSeriesMap().get(seriesId);
				}
			} finally {
				getLock().unlock();
			}
		} else {
			index = getSeriesMap().get(seriesId);
		}

		return seriesFieldMap;
		/*
		 * lock.lock(); try { if ((series = seriesFieldMap.get(valueFieldName)) == null)
		 * { ByteString seriesId2 = new ByteString(seriesId + SERIESID_SEPARATOR +
		 * valueFieldName); series = new TimeSeries(this, seriesId2, timeBucketSize,
		 * metadata, fp, conf); if (enableMetricsCapture) {
		 * metricsTimeSeriesCounter.inc(); }
		 * seriesFieldMap.getOrCreateSeries(valueFieldName, series);
		 * appendTimeseriesToMeasurementMetadata(seriesId2, fp, timeBucketSize, index);
		 * final SeriesFieldMap tmp = seriesFieldMap; logger.fine(() ->
		 * "Created new timeseries:" + tmp + " for measurement:" + measurementName +
		 * "\t" + seriesId + "\t" + metadata.getRetentionHours() + "\t" +
		 * seriesList.size()); } } finally { lock.unlock(); } }
		 * 
		 */
	}

	public static void indexRowKey(TagIndex tagIndex, int rowIdx, List<Tag> tags) throws IOException {
		for (Tag tag : tags) {
			tagIndex.index(tag.getTagKey(), tag.getTagValue(), rowIdx);
		}
	}

	public default ByteString encodeTagsToString(TagIndex tagIndex, List<Tag> tags) throws IOException {
		StringBuilder builder = new StringBuilder(tags.size() * 5);
		serializeTagForKey(builder, tags.get(0));
		for (int i = 1; i < tags.size(); i++) {
			Tag tag = tags.get(i);
			builder.append(TAG_SEPARATOR);
			serializeTagForKey(builder, tag);
		}
		String rowKey = builder.toString();
		return new ByteString(rowKey);
	}

	public static void serializeTagForKey(StringBuilder builder, Tag tag) {
		builder.append(tag.getTagKey());
		builder.append(TAG_KV_SEPARATOR);
		builder.append(tag.getTagValue());
	}

	public default ByteString constructSeriesId(List<Tag> tags, TagIndex index) throws IOException {
		return encodeTagsToString(index, tags);
	}

	public static List<Tag> decodeStringToTags(TagIndex tagIndex, ByteString tagString) throws IOException {
		List<Tag> tagList = new ArrayList<>();
		if (tagString == null || tagString.isEmpty()) {
			return tagList;
		}
		for (ByteString tag : tagString.split(TAG_SEPARATOR)) {
			ByteString[] split = tag.split(TAG_KV_SEPARATOR);
			if (split.length != 2) {
				throw SEARCH_REJECT;
			}
			tagList.add(Tag.newBuilder().setTagKey(split[0].toString()).setTagValue(split[1].toString()).build());
		}
		return tagList;
	}

	public String getMeasurementName();

	public default List<List<Tag>> getTagsForMeasurement() throws Exception {
		Set<ByteString> keySet = getSeriesKeys();
		List<List<Tag>> tagList = new ArrayList<>();
		for (ByteString entry : keySet) {
			List<Tag> tags = decodeStringToTags(getTagIndex(), entry);
			tagList.add(tags);
		}
		return tagList;
	}

	public default Set<ByteString> getTagFilteredRowKeys(TagFilter tagFilterTree) throws IOException {
		return getTagIndex().searchRowKeysForTagFilter(tagFilterTree);
	}

	public default void addPointLocked(Point dp, boolean preSorted) throws IOException {
		Series fieldMap = getOrCreateSeriesFieldMap(new ArrayList<>(dp.getTagsList()), preSorted);
		fieldMap.addPoint(dp, this);
	}

	public default void addPointUnlocked(Point dp, boolean preSorted) throws IOException {
		Series fieldMap = getOrCreateSeriesFieldMap(new ArrayList<>(dp.getTagsList()), preSorted);
		fieldMap.addPoint(dp, this);
	}

	public int getTimeBucketSize();

//	public default void collectGarbage(Archiver archiver) throws IOException {
//		runCleanupOperation("garbage collection", fieldBucket -> {
//			try {
//				Map<Integer, List<ValueWriter>> collectedGarbage = fieldBucket.collectGarbage();
//				List<Writer> output = new ArrayList<>();
//				getLogger().fine("Collected garbage:" + collectedGarbage.size());
//				if (archiver != null && collectedGarbage != null) {
//					for (Entry<Integer, List<Writer>> entry : collectedGarbage.entrySet()) {
//						for (Writer writer : entry.getValue()) {
//							byte[] buf = Archiver.writerToByteArray(writer);
//							TimeSeriesArchivalObject archivalObject = new TimeSeriesArchivalObject(getDbName(),
//									getMeasurementName(), fieldBucket.getFieldId(), entry.getKey(), buf);
//							try {
//								archiver.archive(archivalObject);
//							} catch (ArchiveException e) {
//								getLogger().log(Level.SEVERE, "Series failed to archive, series:" + fieldBucket.getFieldId()
//										+ " db:" + getDbName() + " m:" + getMeasurementName(), e);
//							}
//							output.add(writer);
//						}
//					}
//				}
//				return output;
//			} catch (IOException e) {
//				throw new RuntimeException(e);
//			}
//		});
//	}

	public default Set<String> compact() throws IOException {
		return runCleanupOperation("compacting", ts -> {
			try {
				return ts.compact();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	public default Set<String> runCleanupOperation(String operation,
			java.util.function.Function<Series, List<Writer>> op) throws IOException {
		Set<String> cleanupList = new HashSet<>();
		getLock().lock();
		try {
			for (Series entry : getSeriesList()) {
				try {
					List<Writer> list = op.apply(entry);
					if (list == null) {
						continue;
					}
					for (Writer timeSeriesBucket : list) {
						cleanupList.add(timeSeriesBucket.getBufferId().toString());
						getLogger().fine("Adding buffer to cleanup " + operation + " for bucket:" + entry.getSeriesId()
								+ " Offset:" + timeSeriesBucket.currentOffset());
					}
					getLogger().fine("Buffers " + operation + " for time series:" +entry.getSeriesId());
				} catch (Exception e) {
					getLogger().log(Level.SEVERE, "Error collecing " + operation, e);
				}
			}
			// cleanup these buffer ids
			if (cleanupList.size() > 0) {
				getLogger().info(
						"For measurement:" + getMeasurementName() + " cleaned=" + cleanupList.size() + " buffers");
			}
			getMalloc().cleanupBufferIds(cleanupList);
		} finally {
			getLock().unlock();
		}
		return cleanupList;
	}

	public default Series getSeriesField(List<Tag> tags) throws IOException {
		Collections.sort(tags, TAG_COMPARATOR);
		ByteString rowKey = constructSeriesId(tags, getTagIndex());
		// check and create timeseries
		Series map = getSeriesFromKey(rowKey);
		return map;
	}

	public default Set<String> getFieldsForMeasurement() {
		Set<String> results = new HashSet<>();
		Set<ByteString> keySet = getSeriesKeys();
		for (ByteString key : keySet) {
			Series map = getSeriesFromKey(key);
			results.addAll(map.getFields());
		}
		return results;
	}

	public default void queryDataPoints(String valueFieldNamePattern, long startTime, long endTime, TagFilter tagFilter,
			Predicate valuePredicate, List<SeriesOutput> resultMap) throws IOException {
		final Set<ByteString> rowKeys;
		if (tagFilter == null) {
			rowKeys = getSeriesKeys();
		} else {
			rowKeys = getTagFilteredRowKeys(tagFilter);
		}
		getLogger()
				.fine(() -> "Filtered row keys to query(" + valueFieldNamePattern + "," + tagFilter + "):" + rowKeys);
		final Pattern p;
		try {
			p = Pattern.compile(valueFieldNamePattern);
		} catch (Exception e) {
			throw new IOException("Invalid regex for value field name:" + e.getMessage());
		}
		Set<ByteString> outputKeys = new HashSet<>();
		final Map<ByteString, List<String>> fields = new HashMap<>();
		if (rowKeys != null) {
			for (ByteString key : rowKeys) {
				List<String> fieldMap = new ArrayList<>();
				Set<String> fieldSet = getSeriesFromKey(key).getFields();
				getLogger().fine(() -> "Row key:" + key + " Fields:" + fieldSet);
				for (String fieldSetEntry : fieldSet) {
					if (p.matcher(fieldSetEntry).matches()) {
						fieldMap.add(fieldSetEntry);
					}
				}
				if (fieldMap.size() > 0) {
					fields.put(key, fieldMap);
					outputKeys.add(key);
				}
			}
		}

		Stream<ByteString> stream = outputKeys.stream();
		if (useQueryPool()) {
			stream = stream.parallel();
		}
		getLogger().fine(() -> "Output keys:" + outputKeys.size());
		stream.forEach(entry -> {
			try {
				List<String> valueFieldNames = fields.get(entry);
				if (valueFieldNames == null) {
					throw new NullPointerException(
							"NPEfor:" + entry + " rowkeys:" + fields + " vfn:" + valueFieldNamePattern);
				}
				populateDataPoints(valueFieldNames, entry, startTime, endTime, valuePredicate, p, resultMap);
			} catch (Exception e) {
				getLogger().log(Level.SEVERE, "Failed to query data points", e);
			}
		});
	}

	public default void populateDataPoints(List<String> valueFieldNames, ByteString rowKey, long startTime,
			long endTime, Predicate valuePredicate, Pattern p, List<SeriesOutput> resultMap) throws IOException {
		List<Tag> seriesTags = decodeStringToTags(getTagIndex(), rowKey);
		Series seriesFieldMap = getSeriesFromKey(rowKey);

		Map<String, List<DataPoint>> queryDataPoints = seriesFieldMap.queryDataPoints(this, valueFieldNames, startTime,
				endTime, valuePredicate);
		for (Entry<String, List<DataPoint>> entry : queryDataPoints.entrySet()) {
			getLogger().fine(() -> "Reading datapoints for:" + entry.getKey());
			SeriesOutput seriesQueryOutput = new SeriesOutput(getMeasurementName(), entry.getKey(), seriesTags);
			seriesQueryOutput.setFp(seriesFieldMap.isFp(entry.getKey()));
			seriesQueryOutput.setDataPoints(entry.getValue());
			resultMap.add(seriesQueryOutput);
		}
	}

	// public default void queryReaders(String valueFieldName, long startTime, long
	// endTime,
	// LinkedHashMap<ValueReader, Boolean> readers) throws IOException {
	// for (ByteString entry : getSeriesKeys()) {
	// Series m = getSeriesFromKey(new ByteString(entry));
	// TimeBucket series = m.get(valueFieldName);
	// if (series == null) {
	// continue;
	// }
	// List<Tag> seriesTags = decodeStringToTags(getTagIndex(), entry);
	// for (ValueReader reader : series.queryReader(valueFieldName, seriesTags,
	// startTime, endTime, null)) {
	// readers.put(reader, series.isFp());
	// }
	// }
	// }
	//
	// public default void queryReadersWithMap(String valueFieldName, long
	// startTime, long endTime,
	// LinkedHashMap<ValueReader, List<Tag>> readers) throws IOException {
	// for (ByteString entry : getSeriesKeys()) {
	// Series m = getSeriesFromKey(new ByteString(entry));
	// TimeBucket series = m.get(valueFieldName);
	// if (series == null) {
	// continue;
	// }
	// List<Tag> seriesTags = decodeStringToTags(getTagIndex(), entry);
	// for (ValueReader reader : series.queryReader(valueFieldName, seriesTags,
	// startTime, endTime, null)) {
	// readers.put(reader, seriesTags);
	// }
	// }
	// }

	public default Collection<String> getTagKeys() throws IOException {
		return getTagIndex().getTagKeys();
	}

//	public default Collection<FieldBucket> getTimeSeries() {
//		List<FieldBucket> series = new ArrayList<>();
//		for (Series seriesFieldMap : getSeriesList()) {
//			series.addAll(seriesFieldMap.values());
//		}
//		return series;
//	}

	/**
	 * List all series field maps
	 * 
	 * @return
	 */
	public List<Series> getSeriesList();

	public Logger getLogger();

	public default SortedMap<Integer, List<Writer>> createNewBucketMap(ByteString seriesId) {
		return new ConcurrentSkipListMap<>();
	}

	public ReentrantLock getLock();

	public boolean useQueryPool();

	public String getDbName();

	public Malloc getMalloc();

	public default Collection<String> getTagValues(String tagKey) {
		return getTagIndex().getTagValues(tagKey);
	}

	public default boolean isFieldFp(String valueFieldName) throws ItemNotFoundException {
		for (ByteString entry : getSeriesKeys()) {
			Series seriesFromKey = getSeriesFromKey(entry);
			return seriesFromKey.isFp(valueFieldName);
		}
		throw StorageEngine.NOT_FOUND_EXCEPTION;
	}

	public static class TagComparator implements Comparator<Tag> {

		@Override
		public int compare(Tag o1, Tag o2) {
			int r = o1.getTagKey().compareTo(o2.getTagKey());
			if (r != 0) {
				return r;
			} else {
				return o1.getTagValue().compareTo(o2.getTagValue());
			}
		}
	}

	public DBMetadata getMetadata();

	public default void appendTimeseriesToMeasurementMetadata(ByteString fieldId, boolean fp, int timeBucketSize,
			int idx) throws IOException {
		// do nothing default implementation
	}

	public Map<String, String> getConf();

	Map<ByteString, Integer> getSeriesMap();

	boolean isEnableMetricsCapture();

	Counter getMetricsTimeSeriesCounter();

	/**
	 * Update retention hours for this TimeSeries
	 * 
	 * @param retentionHours
	 */
	public default void setRetentionHours(int retentionHours) {
		int val = (int) (((long) retentionHours * 3600) / getTimeBucketSize());
		if (val < 1) {
			getLogger().fine("Incorrect bucket(" + getTimeBucketSize() + ") or retention(" + retentionHours
					+ ") configuration; correcting to 1 bucket for measurement:" + getMeasurementName());
			val = 1;
		}
		getRetentionBuckets().set(val);
	}

	public AtomicInteger getRetentionBuckets();

}
