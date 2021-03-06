/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.lineage;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;

import org.apache.sysds.api.DMLScript;
import org.apache.sysds.hops.OptimizerUtils;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.lineage.LineageCacheConfig.LineageCacheStatus;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.util.LocalFileUtils;

public class LineageCacheEviction
{
	private static long _cachesize = 0;
	private static long CACHE_LIMIT; //limit in bytes
	protected static final HashSet<LineageItem> _removelist = new HashSet<>();
	private static final Map<LineageItem, SpilledItem> _spillList = new HashMap<>();
	private static String _outdir = null;
	private static TreeSet<LineageCacheEntry> weightedQueue = new TreeSet<>(LineageCacheConfig.LineageCacheComparator);
	
	protected static void resetEviction() {
		// reset cache size, otherwise the cache clear leads to unusable 
		// space which means evictions could run into endless loops
		_cachesize = 0;
		_spillList.clear();
		weightedQueue.clear();
		_outdir = null;
		if (DMLScript.STATISTICS)
			_removelist.clear();
	}

	//--------------- CACHE MAINTENANCE & LOOKUP FUNCTIONS --------------//
	
	protected static void addEntry(LineageCacheEntry entry) {
		if (entry.isNullVal())
			// Placeholders shouldn't participate in eviction cycles.
			return;

		double exectime = ((double) entry._computeTime) / 1000000; // in milliseconds
		if (!entry.isMatrixValue() && exectime >= LineageCacheConfig.MIN_SPILL_TIME_ESTIMATE)
			// Pin the entries having scalar values and with higher computation time
			// to memory, to save those from eviction. Scalar values are
			// not spilled to disk and are just deleted. Scalar entries associated 
			// with high computation time might contain function outputs. Pinning them
			// will increase chances of multilevel reuse.
			entry.setCacheStatus(LineageCacheStatus.PINNED);

		if (entry.isMatrixValue() || exectime < LineageCacheConfig.MIN_SPILL_TIME_ESTIMATE) {
			// Don't add the memory pinned entries in weighted queue. 
			// The eviction queue should contain only entries that can
			// be removed or spilled to disk.
			entry.setTimestamp();
			weightedQueue.add(entry);
		}
	}
	
	protected static void getEntry(LineageCacheEntry entry) {
		// Reset the timestamp to maintain the LRU component of the scoring function
		if (!LineageCacheConfig.isLRU()) 
			return;
		
		if (weightedQueue.remove(entry)) {
			entry.setTimestamp();
			weightedQueue.add(entry);
		}
	}

	private static void removeEntry(Map<LineageItem, LineageCacheEntry> cache, LineageCacheEntry e) {
		if (cache.remove(e._key) != null)
			_cachesize -= e.getSize();

		if (DMLScript.STATISTICS) {
			_removelist.add(e._key);
			LineageCacheStatistics.incrementMemDeletes();
		}
		// NOTE: The caller of this method maintains the eviction queue.
	}
	private static void removeOrSpillEntry(Map<LineageItem, LineageCacheEntry> cache, LineageCacheEntry e, boolean spill) {
		if (e._origItem == null) {
			// Single entry. Remove or spill.
			if (spill)
				spillToLocalFS(cache, e);
			else
				removeEntry(cache, e);
			return;
		}
		
		// Defer the eviction till all the entries with the same matrix are evicted.
		e.setCacheStatus(spill ? LineageCacheStatus.TOSPILL : LineageCacheStatus.TODELETE);

		// If all the entries with the same data are evicted, check if deferred spilling 
		// is set for any of those. If so, spill the matrix to disk and set null in the 
		// cache entries. Keeping the spilled entries removes the need to use another 
		// data structure and also maintains the connections between items pointing to the 
		// same data. Delete all the entries if all are set to be deleted.
		boolean write = false;
		LineageCacheEntry tmp = cache.get(e._origItem); //head
		while (tmp != null) {
			if (tmp.getCacheStatus() != LineageCacheStatus.TOSPILL
				&& tmp.getCacheStatus() != LineageCacheStatus.TODELETE)
				return; //do nothing

			write |= (tmp.getCacheStatus() == LineageCacheStatus.TOSPILL);
			tmp = tmp._nextEntry;
		}
		if (write) {
			// Spill to disk if at least one entry has status TOSPILL. 
			spillToLocalFS(cache, cache.get(e._origItem));
			LineageCacheEntry h = cache.get(e._origItem);
			while (h != null) {
				// Set values to null for all the entries.
				h.setNullValues();
				// Set status to spilled for all the entries.
				h.setCacheStatus(LineageCacheStatus.SPILLED);
				h = h._nextEntry;
			}
			// Reduce cachesize once for all the entries.
			updateSize(e.getSize(), false);
			// Keep them in cache.
			return;
		}
		// All are set to be deleted.
		else {
			// Remove all the entries from cache.
			LineageCacheEntry h = cache.get(e._origItem);
			while (h != null) {
				removeEntry(cache, h);
				h = h._nextEntry;
			}
		}
		// NOTE: The callers of this method maintain the eviction queue.
	}

	//---------------- CACHE SPACE MANAGEMENT METHODS -----------------//
	
	protected static void setCacheLimit(long limit) {
		CACHE_LIMIT = limit;
	}

	//Note: public for spilling tests
	public static long getCacheLimit() {
		return CACHE_LIMIT;
	}
	
	protected static void updateSize(long space, boolean addspace) {
		if (addspace)
			_cachesize += space;
		else
			_cachesize -= space;
	}

	protected static boolean isBelowThreshold(long spaceNeeded) {
		return ((spaceNeeded + _cachesize) <= CACHE_LIMIT);
	}

	protected static void makeSpace(Map<LineageItem, LineageCacheEntry> cache, long spaceNeeded) {
		//Cost based eviction
		LineageCacheEntry e = weightedQueue.pollFirst();
		while (e != null)
		{
			if ((spaceNeeded + _cachesize) <= CACHE_LIMIT)
				// Enough space recovered.
				break;

			if (!LineageCacheConfig.isSetSpill()) {
				// If eviction is disabled, just delete the entries.
				removeOrSpillEntry(cache, e, false);
				e = weightedQueue.pollFirst();
				continue;
			}

			if (!e.getCacheStatus().canEvict()) {
				// Note: Execution should never reach here, as these 
				//       entries are not part of the weightedQueue.
				continue;
				//TODO: Graceful handling of status.
			}

			double exectime = ((double) e._computeTime) / 1000000; // in milliseconds

			if (!e.isMatrixValue()) {
				// No spilling for scalar entries. Just delete those.
				// Note: scalar entries with higher computation time are pinned.
				removeOrSpillEntry(cache, e, false);
				e = weightedQueue.pollFirst();
				continue;
			}

			// Estimate time to write to FS + read from FS.
			double spilltime = getDiskSpillEstimate(e) * 1000; // in milliseconds

			if (LineageCache.DEBUG) {
				if (exectime > LineageCacheConfig.MIN_SPILL_TIME_ESTIMATE) {
					System.out.print("LI " + e._key.getOpcode());
					System.out.print(" exec time " + ((double) e._computeTime) / 1000000);
					System.out.print(" estimate time " + getDiskSpillEstimate(e) * 1000);
					System.out.print(" dim " + e.getMBValue().getNumRows() + " " + e.getMBValue().getNumColumns());
					System.out.println(" size " + getDiskSizeEstimate(e));
				}
			}

			if (spilltime < LineageCacheConfig.MIN_SPILL_TIME_ESTIMATE) {
				// Can't trust the estimate if less than 100ms.
				// Spill if it takes longer to recompute.
				if (exectime >= LineageCacheConfig.MIN_SPILL_TIME_ESTIMATE)
					//spillToLocalFS(e);
					removeOrSpillEntry(cache, e, true);  //spill
				else
					removeOrSpillEntry(cache, e, false); //delete
			}
			else {
				// Spill if it takes longer to recompute than spilling.
				if (exectime > spilltime)
					//spillToLocalFS(e);
					removeOrSpillEntry(cache, e, true);  //spill
				else
					removeOrSpillEntry(cache, e, false); //delete
			}

			// Remove the entry from cache.
			e = weightedQueue.pollFirst();
		}
	}

	//---------------- COSTING RELATED METHODS -----------------

	private static double getDiskSpillEstimate(LineageCacheEntry e) {
		if (!e.isMatrixValue() || e.isNullVal())
			return 0;
		// This includes sum of writing to and reading from disk
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		double size = getDiskSizeEstimate(e);
		double loadtime = isSparse(e) ? size/LineageCacheConfig.FSREAD_SPARSE : size/LineageCacheConfig.FSREAD_DENSE;
		double writetime = isSparse(e) ? size/LineageCacheConfig.FSWRITE_SPARSE : size/LineageCacheConfig.FSWRITE_DENSE;

		//double loadtime = CostEstimatorStaticRuntime.getFSReadTime(r, c, s);
		//double writetime = CostEstimatorStaticRuntime.getFSWriteTime(r, c, s);
		if (DMLScript.STATISTICS) 
			LineageCacheStatistics.incrementCostingTime(System.nanoTime() - t0);
		return loadtime + writetime;
	}

	private static double getDiskSizeEstimate(LineageCacheEntry e) {
		if (!e.isMatrixValue() || e.isNullVal())
			return 0;
		MatrixBlock mb = e.getMBValue();
		long r = mb.getNumRows();
		long c = mb.getNumColumns();
		long nnz = mb.getNonZeros();
		double s = OptimizerUtils.getSparsity(r, c, nnz);
		double disksize = ((double)MatrixBlock.estimateSizeOnDisk(r, c, (long)(s*r*c))) / (1024*1024);
		return disksize;
	}
	
	private static void adjustReadWriteSpeed(LineageCacheEntry e, double IOtime, boolean read) {
		double size = getDiskSizeEstimate(e);
		if (!e.isMatrixValue() || size < LineageCacheConfig.MIN_SPILL_DATA)
			// Scalar or too small
			return; 
		
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		double newIOSpeed = size / IOtime; // MB per second 
		// Adjust the read/write speed taking into account the last read/write.
		// These constants will eventually converge to the real speed.
		if (read) {
			if (isSparse(e))
				LineageCacheConfig.FSREAD_SPARSE = (LineageCacheConfig.FSREAD_SPARSE + newIOSpeed) / 2;
			else
				LineageCacheConfig.FSREAD_DENSE= (LineageCacheConfig.FSREAD_DENSE+ newIOSpeed) / 2;
		}
		else {
			if (isSparse(e))
				LineageCacheConfig.FSWRITE_SPARSE = (LineageCacheConfig.FSWRITE_SPARSE + newIOSpeed) / 2;
			else
				LineageCacheConfig.FSWRITE_DENSE= (LineageCacheConfig.FSWRITE_DENSE+ newIOSpeed) / 2;
		}
		if (DMLScript.STATISTICS) 
			LineageCacheStatistics.incrementCostingTime(System.nanoTime() - t0);
	}
	
	private static boolean isSparse(LineageCacheEntry e) {
		if (!e.isMatrixValue() || e.isNullVal())
			return false;
		return e.getMBValue().isInSparseFormat();
	}

	// ---------------- I/O METHODS TO LOCAL FS -----------------
	
	private static void spillToLocalFS(Map<LineageItem, LineageCacheEntry> cache, LineageCacheEntry entry) {
		if (!entry.isMatrixValue())
			throw new DMLRuntimeException ("Spilling scalar objects to disk is not allowd. Key: "+entry._key);
		if (entry.isNullVal())
			throw new DMLRuntimeException ("Cannot spill null value to disk. Key: "+entry._key);
		
		long t0 = System.nanoTime();
		if (_outdir == null) {
			_outdir = LocalFileUtils.getUniqueWorkingDir(LocalFileUtils.CATEGORY_LINEAGE);
			LocalFileUtils.createLocalFileIfNotExist(_outdir);
		}
		String outfile = _outdir+"/"+entry._key.getId();
		try {
			LocalFileUtils.writeMatrixBlockToLocal(outfile, entry.getMBValue());
		} catch (IOException e) {
			throw new DMLRuntimeException ("Write to " + outfile + " failed.", e);
		}
		long t1 = System.nanoTime();
		// Adjust disk writing speed
		adjustReadWriteSpeed(entry, ((double)(t1-t0))/1000000000, false);
		
		// Add all the entries associated with this matrix to spillList.
		if (entry._origItem == null) {
			_spillList.put(entry._key, new SpilledItem(outfile));
		}
		else {
			LineageCacheEntry h = cache.get(entry._origItem); //head
			while (h != null) {
				_spillList.put(h._key, new SpilledItem(outfile));
				h = h._nextEntry;
			}
		}

		if (DMLScript.STATISTICS) {
			LineageCacheStatistics.incrementFSWriteTime(t1-t0);
			LineageCacheStatistics.incrementFSWrites();
		}
	}

	protected static LineageCacheEntry readFromLocalFS(Map<LineageItem, LineageCacheEntry> cache, LineageItem key) {
		if (cache.get(key) == null)
			throw new DMLRuntimeException ("Spilled item should present in cache. Key: "+key);

		long t0 = System.nanoTime();
		MatrixBlock mb = null;
		// Read from local FS
		try {
			mb = LocalFileUtils.readMatrixBlockFromLocal(_spillList.get(key)._outfile);
		} catch (IOException e) {
			throw new DMLRuntimeException ("Read from " + _spillList.get(key)._outfile + " failed.", e);
		}
		LocalFileUtils.deleteFileIfExists(_spillList.get(key)._outfile, true);
		long t1 = System.nanoTime();

		// Restore to cache
		LineageCacheEntry e = cache.get(key);
		e.setValue(mb);
		if (e._origItem != null) {
			// Restore to all the entries having the same data.
			LineageCacheEntry h = cache.get(e._origItem); //head
			while (h != null) {
				h.setValue(mb);
				h = h._nextEntry;
			}
			// Increase cachesize once for all the entries.
			updateSize(e.getSize(), true);
		}

		// Adjust disk reading speed
		adjustReadWriteSpeed(e, ((double)(t1-t0))/1000000000, true);
		// TODO: set cache status as RELOADED for this entry
		_spillList.remove(key);
		if (DMLScript.STATISTICS) {
			LineageCacheStatistics.incrementFSReadTime(t1-t0);
			LineageCacheStatistics.incrementFSHits();
		}
		return cache.get(key);
	}
	
	protected static boolean spillListContains(LineageItem key) {
		return _spillList.containsKey(key);
	}

	// ---------------- INTERNAL DATA STRUCTURES FOR EVICTION -----------------

	// TODO: Remove this class, and add outfile to LineageCacheEntry.
	private static class SpilledItem {
		String _outfile;
		//long _computeTime;
		//protected LineageItem _origItem;

		public SpilledItem(String outfile) {
			_outfile = outfile;
			//_computeTime = computetime;
			//_origItem = origItem;
		}
	}
}
