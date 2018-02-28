/**
 * Copyright 2018 Ambud Sharma
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
package com.srotya.sidewinder.core.filters.tags;

import com.srotya.sidewinder.core.filters.Filter;
import com.srotya.sidewinder.core.storage.TagIndex;

public abstract class TagFilter implements Filter<TagIndex> {
	
	private String tagKey;
	private String comparedValue;
	
	public TagFilter(String tagKey, String comparedValue) {
		this.tagKey = tagKey;
		this.comparedValue = comparedValue;
	}
	/**
	 * @return the tagKey
	 */
	public String getTagKey() {
		return tagKey;
	}
	/**
	 * @param tagKey the tagKey to set
	 */
	public void setTagKey(String tagKey) {
		this.tagKey = tagKey;
	}
	/**
	 * @return the comparedValue
	 */
	public String getComparedValue() {
		return comparedValue;
	}
	/**
	 * @param comparedValue the comparedValue to set
	 */
	public void setComparedValue(String comparedValue) {
		this.comparedValue = comparedValue;
	}
	
}
