/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.server.dao.sql.attributes;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.server.cache.TbTransactionalCache;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.dao.AbstractDaoServiceTest;
import org.thingsboard.server.dao.attributes.AttributeCacheKey;
import org.thingsboard.server.dao.attributes.CachedAttributesService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class AttributeServiceTest extends AbstractDaoServiceTest {

    private static final String OLD_VALUE = "OLD VALUE";
    private static final String NEW_VALUE = "NEW VALUE";

    @Autowired
    private TbTransactionalCache<AttributeCacheKey, AttributeKvEntry> cache;

    @Autowired
    private CachedAttributesService attributesService;

    @Test
    public void testDummyRequestWithEmptyResult() throws Exception {
        var future = attributesService.find(new TenantId(UUID.randomUUID()), new DeviceId(UUID.randomUUID()), DataConstants.SERVER_SCOPE, "TEST");
        Assert.assertNotNull(future);
        var result = future.get(10, TimeUnit.SECONDS);
        Assert.assertTrue(result.isEmpty());
    }

    @Test
    public void testConcurrentTransaction() throws Exception {
        var tenantId = new TenantId(UUID.randomUUID());
        var deviceId = new DeviceId(UUID.randomUUID());
        var scope = DataConstants.SERVER_SCOPE;
        var key = "TEST";

        var attrKey = new AttributeCacheKey(scope, deviceId, "TEST");
        var oldValue = new BaseAttributeKvEntry(System.currentTimeMillis(), new StringDataEntry(key, OLD_VALUE));
        var newValue = new BaseAttributeKvEntry(System.currentTimeMillis(), new StringDataEntry(key, NEW_VALUE));

        var trx = cache.newTransactionForKey(attrKey);
        cache.putIfAbsent(attrKey, newValue);
        trx.putIfAbsent(attrKey, oldValue);
        Assert.assertFalse(trx.commit());
        Assert.assertEquals(NEW_VALUE, getAttributeValue(tenantId, deviceId, scope, key));
    }

    @Test
    public void testConcurrentFetchAndUpdate() throws Exception {
        var tenantId = new TenantId(UUID.randomUUID());
        ListeningExecutorService pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));
        try {
            for (int i = 0; i < 100; i++) {
                var deviceId = new DeviceId(UUID.randomUUID());
                testConcurrentFetchAndUpdate(tenantId, deviceId, pool);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void testConcurrentFetchAndUpdateMulti() throws Exception {
        var tenantId = new TenantId(UUID.randomUUID());
        ListeningExecutorService pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(2));
        try {
            for (int i = 0; i < 100; i++) {
                var deviceId = new DeviceId(UUID.randomUUID());
                testConcurrentFetchAndUpdateMulti(tenantId, deviceId, pool);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void testFetchAndUpdateEmpty() throws Exception {
        var tenantId = new TenantId(UUID.randomUUID());
        var deviceId = new DeviceId(UUID.randomUUID());
        var scope = DataConstants.SERVER_SCOPE;
        var key = "TEST";

        Optional<AttributeKvEntry> emptyValue = attributesService.find(tenantId, deviceId, scope, key).get(10, TimeUnit.SECONDS);
        Assert.assertTrue(emptyValue.isEmpty());

        saveAttribute(tenantId, deviceId, scope, key, NEW_VALUE);
        Assert.assertEquals(NEW_VALUE, getAttributeValue(tenantId, deviceId, scope, key));
    }

    @Test
    public void testFetchAndUpdateMulti() throws Exception {
        var tenantId = new TenantId(UUID.randomUUID());
        var deviceId = new DeviceId(UUID.randomUUID());
        var scope = DataConstants.SERVER_SCOPE;
        var key1 = "TEST1";
        var key2 = "TEST2";

        var value = getAttributeValues(tenantId, deviceId, scope, Arrays.asList(key1, key2));
        Assert.assertTrue(value.isEmpty());

        saveAttribute(tenantId, deviceId, scope, key1, OLD_VALUE);

        value = getAttributeValues(tenantId, deviceId, scope, Arrays.asList(key1, key2));
        Assert.assertEquals(1, value.size());
        Assert.assertEquals(OLD_VALUE, value.get(0));

        saveAttribute(tenantId, deviceId, scope, key2, NEW_VALUE);

        value = getAttributeValues(tenantId, deviceId, scope, Arrays.asList(key1, key2));
        Assert.assertEquals(2, value.size());
        Assert.assertTrue(value.contains(OLD_VALUE));
        Assert.assertTrue(value.contains(NEW_VALUE));

        saveAttribute(tenantId, deviceId, scope, key1, NEW_VALUE);

        value = getAttributeValues(tenantId, deviceId, scope, Arrays.asList(key1, key2));
        Assert.assertEquals(2, value.size());
        Assert.assertEquals(NEW_VALUE, value.get(0));
        Assert.assertEquals(NEW_VALUE, value.get(1));
    }

    private void testConcurrentFetchAndUpdate(TenantId tenantId, DeviceId deviceId, ListeningExecutorService pool) throws Exception {
        var scope = DataConstants.SERVER_SCOPE;
        var key = "TEST";
        saveAttribute(tenantId, deviceId, scope, key, OLD_VALUE);
        List<ListenableFuture<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            var value = getAttributeValue(tenantId, deviceId, scope, key);
            Assert.assertTrue(value.equals(OLD_VALUE) || value.equals(NEW_VALUE));
        }));
        futures.add(pool.submit(() -> saveAttribute(tenantId, deviceId, scope, key, NEW_VALUE)));
        Futures.allAsList(futures).get(10, TimeUnit.SECONDS);
        Assert.assertEquals(NEW_VALUE, getAttributeValue(tenantId, deviceId, scope, key));
    }

    private void testConcurrentFetchAndUpdateMulti(TenantId tenantId, DeviceId deviceId, ListeningExecutorService pool) throws Exception {
        var scope = DataConstants.SERVER_SCOPE;
        var key1 = "TEST1";
        var key2 = "TEST2";
        saveAttribute(tenantId, deviceId, scope, key1, OLD_VALUE);
        saveAttribute(tenantId, deviceId, scope, key2, OLD_VALUE);
        List<ListenableFuture<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            var value = getAttributeValues(tenantId, deviceId, scope, Arrays.asList(key1, key2));
            Assert.assertEquals(2, value.size());
            Assert.assertTrue(value.contains(OLD_VALUE) || value.contains(NEW_VALUE));
        }));
        futures.add(pool.submit(() -> {
            saveAttribute(tenantId, deviceId, scope, key1, NEW_VALUE);
            saveAttribute(tenantId, deviceId, scope, key2, NEW_VALUE);
        }));
        Futures.allAsList(futures).get(10, TimeUnit.SECONDS);
        var newResult = getAttributeValues(tenantId, deviceId, scope, Arrays.asList(key1, key2));
        Assert.assertEquals(2, newResult.size());
        Assert.assertEquals(NEW_VALUE, newResult.get(0));
        Assert.assertEquals(NEW_VALUE, newResult.get(1));
    }

    private String getAttributeValue(TenantId tenantId, DeviceId deviceId, String scope, String key) {
        try {
            Optional<AttributeKvEntry> entry = attributesService.find(tenantId, deviceId, scope, key).get(10, TimeUnit.SECONDS);
            return entry.orElseThrow(RuntimeException::new).getStrValue().orElse("Unknown");
        } catch (Exception e) {
            log.warn("Failed to get attribute", e.getCause());
            throw new RuntimeException(e);
        }
    }

    private List<String> getAttributeValues(TenantId tenantId, DeviceId deviceId, String scope, List<String> keys) {
        try {
            List<AttributeKvEntry> entry = attributesService.find(tenantId, deviceId, scope, keys).get(10, TimeUnit.SECONDS);
            return entry.stream().map(e -> e.getStrValue().orElse(null)).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to get attributes", e.getCause());
            throw new RuntimeException(e);
        }
    }

    private void saveAttribute(TenantId tenantId, DeviceId deviceId, String scope, String key, String s) {
        try {
            AttributeKvEntry newEntry = new BaseAttributeKvEntry(System.currentTimeMillis(), new StringDataEntry(key, s));
            attributesService.save(tenantId, deviceId, scope, Collections.singletonList(newEntry)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to save attribute", e.getCause());
            Assert.assertNull(e);
        }
    }


}