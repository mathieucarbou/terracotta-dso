/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.search;

import com.tc.object.metadata.NVPair;
import com.tc.objectserver.metadata.MetaDataProcessingContext;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IndexManager {

  void removeIfValueEqual(String indexName, Map<Object, Object> toRemove, MetaDataProcessingContext metaDataContext)
      throws IndexException;

  void remove(String indexName, Object key, MetaDataProcessingContext metaDataContext) throws IndexException;

  void upsert(String indexName, Object key, Object value, List<NVPair> attributes,
              MetaDataProcessingContext metaDataContext, boolean onlyIfAbsent) throws IndexException;

  void clear(String indexName, MetaDataProcessingContext metaDataContext) throws IndexException;

  public SearchResult searchIndex(String indexName, LinkedList queryStack, boolean includeKeys, boolean includeValues,
                                  Set<String> attributeSet, List<NVPair> sortAttributes, List<NVPair> aggregators,
                                  int maxResults) throws IndexException;

  public Map<String, List<IndexFile>> getFilesToSync();

  void release();

  void shutdown();

  void replace(String indexName, Object key, Object value, Object previousValue, List<NVPair> attributes,
               MetaDataProcessingContext metaDataContext) throws IndexException;
}
