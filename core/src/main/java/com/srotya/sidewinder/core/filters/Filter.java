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
package com.srotya.sidewinder.core.filters;

import com.srotya.sidewinder.core.predicates.Predicate;

/**
 * Filter API is essentially predicate design pattern for non-numeric data.
 * 
 * Note that the two APIs are separated for performance efficiency reasons since
 * numeric data can and should be vectorized for time series use cases however
 * metadata due it's small size doesn't need to be.
 * 
 * Ref {@link Predicate}
 * 
 * @author ambud
 *
 * @param <E>
 */
public interface Filter<E> {

	boolean retain(E value);

}
