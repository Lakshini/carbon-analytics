/*
 *  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.analytics.dataservice.core.indexing;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.analytics.dataservice.core.Constants;
import org.wso2.carbon.analytics.datasource.commons.Record;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsException;
import org.wso2.carbon.analytics.datasource.core.util.GenericUtils;

import com.leansoft.bigqueue.BigQueueImpl;
import com.leansoft.bigqueue.IBigQueue;

/**
 * Manages local indexing data.
 */
public class LocalIndexDataStore {

    private Log log = LogFactory.getLog(LocalIndexDataStore.class);
    
    private AnalyticsDataIndexer indexer;
    
    private Map<Integer, LocalIndexDataQueue> indexDataQueues;
    
    public LocalIndexDataStore(AnalyticsDataIndexer indexer) throws AnalyticsException {
        this.indexer = indexer;
        this.indexDataQueues = new HashMap<Integer, LocalIndexDataQueue>();
        this.refreshLocalIndexShards();
    }
    
    public void refreshLocalIndexShards() throws AnalyticsException {
        this.closeQueues();
        for (int shardIndex : this.indexer.getLocalShards()) {
            this.indexDataQueues.put(shardIndex, new LocalIndexDataQueue(shardIndex));
        }
    }
    
    public void put(List<Record> records) throws AnalyticsException {
        Map<Integer, List<Record>> recordsMap = this.indexer.extractShardedRecords(records);
        LocalIndexDataQueue dataList;
        for (Map.Entry<Integer, List<Record>> entry : recordsMap.entrySet()) {
            dataList = this.indexDataQueues.get(entry.getKey());
            if (dataList == null) {
                continue;
            }
            dataList.enqueue(new IndexOperation(false).setRecords(entry.getValue()));
        }
    }
    
    public void delete(int tenantId, String tableName, List<String> ids) throws AnalyticsException {
        Map<Integer, List<String>> recordsMap = this.indexer.extractShardedIds(ids);
        LocalIndexDataQueue dataList;
        for (Map.Entry<Integer, List<String>> entry : recordsMap.entrySet()) {
            dataList = this.indexDataQueues.get(entry.getKey());
            if (dataList == null) {
                continue;
            }
            dataList.enqueue(new IndexOperation(true).setIds(entry.getValue()).
                    setDeleteTenantId(tenantId).setDeleteTableName(tableName));
        }
    }
    
    private void closeQueues() {
        for (LocalIndexDataQueue queue : this.indexDataQueues.values()) {
            try {
                queue.close();
            } catch (IOException e) {
                log.warn("Error in closing queue: " + e.getMessage(), e);
            }
        }
    }
    
    public void close() {
        this.closeQueues();
    }
    
    public LocalIndexDataQueue getIndexDataQueue(int shardIndex) {
        return this.indexDataQueues.get(shardIndex);
    }
    
    public static class IndexOperation implements Serializable {
        
        private static final long serialVersionUID = 7764589621281488353L;

        private boolean delete;
        
        private List<String> ids;
        
        private int deleteTenantId;
        
        private String deleteTableName;
        
        private List<Record> records;
        
        public IndexOperation() { }
        
        public IndexOperation(boolean delete) {
            this.delete = delete;
        }

        public List<String> getIds() {
            return ids;
        }

        public IndexOperation setIds(List<String> ids) {
            this.ids = ids;
            return this;
        }

        public List<Record> getRecords() {
            return records;
        }

        public IndexOperation setRecords(List<Record> records) {
            this.records = records;
            return this;
        }
        
        public boolean isDelete() {
            return delete;
        }

        public int getDeleteTenantId() {
            return deleteTenantId;
        }

        public IndexOperation setDeleteTenantId(int deleteTenantId) {
            this.deleteTenantId = deleteTenantId;
            return this;
        }

        public String getDeleteTableName() {
            return deleteTableName;
        }

        public IndexOperation setDeleteTableName(String deleteTableName) {
            this.deleteTableName = deleteTableName;
            return this;
        }
        
        public byte[] getBytes() {
            return GenericUtils.serializeObject(this);
        }
        
        public static IndexOperation fromBytes(byte[] data) {
            return (IndexOperation) GenericUtils.deserializeObject(data);
        }
        
    }
    
    /**
     * Local persistent queue implementation. This should be used in a single thread at a time, 
     * due to reliability guarantees it gives with dequeue.
     */
    public static class LocalIndexDataQueue {
        
        private static final String PRIMARY_QUEUE_SUFFIX = "P";
        
        private static final String SECONDARY_QUEUE_SUFFIX = "S";

        private static final int QUEUE_CLEANUP_THRESHOLD = 1000;
        
        private IBigQueue primaryQueue;
        
        private IBigQueue secondaryQueue;
        
        private long secondaryQueueInitialCount;
        
        private long secondaryProcessedCount;
                
        private int removeCount = 0;
        
        public LocalIndexDataQueue(int shardIndex) throws AnalyticsException {
            this.primaryQueue = this.createQueue(shardIndex + PRIMARY_QUEUE_SUFFIX);
            this.secondaryQueue = this.createQueue(shardIndex + SECONDARY_QUEUE_SUFFIX);
        }
        
        private IBigQueue createQueue(String queueId) throws AnalyticsException {
            String path = Constants.DEFAULT_LOCAL_INDEX_STAGING_LOCATION;
            path = GenericUtils.resolveLocation(path);
            try {
                return new BigQueueImpl(path, queueId);
            } catch (IOException e) {
                throw new AnalyticsException("Error in creating queue: " + e.getMessage(), e);
            }
        }
        
        public void enqueue(IndexOperation indexOp) throws AnalyticsException {
            try {
                this.primaryQueue.enqueue(indexOp.getBytes());
            } catch (IOException e) {
                throw new AnalyticsException("Error in index data enqueue: " + e.getMessage(), e);
            }
        }
        
        public void startDequeue() {
            this.secondaryProcessedCount = 0;
            this.secondaryQueueInitialCount = this.secondaryQueue.size();
        }
        
        private void queueDrain(IBigQueue queue, long count) throws IOException {
            long queueSize = queue.size();
            if (count >= queueSize) {
                queue.removeAll();
            } else {
                for (int i = 0; i < count; i++) {
                    queue.dequeue();
                }
            }
        }
        
        public void endDequeue() throws AnalyticsException {
            try {
                this.queueDrain(this.secondaryQueue, this.secondaryProcessedCount);
            } catch (IOException e) {
                throw new AnalyticsException("Error in end dequeue: " + e.getMessage(), e);
            }
        }
        
        public IndexOperation peekNext() throws AnalyticsException {
            try {
                byte[] data;
                if (this.secondaryProcessedCount < this.secondaryQueueInitialCount) {
                    /* the following will not end up in strict FIFO, but it's
                     * rare that the secondary queue processing will also fail,
                     * and even when that happens, it's unlikely you need strict
                     * ordered retrieval of records then */
                    data = this.secondaryQueue.peek();
                    this.secondaryQueue.enqueue(data);
                    this.secondaryQueue.dequeue();
                } else {
                    data = this.primaryQueue.peek();
                    this.secondaryQueue.enqueue(data);
                    this.primaryQueue.dequeue();
                }
                this.secondaryProcessedCount++;
                IndexOperation indexOp = IndexOperation.fromBytes(data);
                this.removeCount++;
                if (this.removeCount > QUEUE_CLEANUP_THRESHOLD) {
                    this.primaryQueue.gc();
                    this.secondaryQueue.gc();
                    this.removeCount = 0;
                }
                return indexOp;
            } catch (IOException e) {
                throw new AnalyticsException("Error in index data peekNext: " + e.getMessage(), e);
            }
        }
        
        public boolean isEmpty() {
            return this.secondaryProcessedCount >= this.secondaryQueueInitialCount && this.primaryQueue.isEmpty();
        }
        
        public long size() {
            return this.primaryQueue.size();
        }
        
        public void close() throws IOException {
            this.primaryQueue.close();
            this.secondaryQueue.close();
        }
        
    }
    
}
