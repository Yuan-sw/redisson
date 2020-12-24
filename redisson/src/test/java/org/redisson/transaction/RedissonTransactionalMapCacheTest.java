package org.redisson.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RTransaction;
import org.redisson.api.TransactionOptions;

public class RedissonTransactionalMapCacheTest extends RedissonBaseTransactionalMapTest {

    @Test
    public void testPutIfAbsentTTL() throws InterruptedException {
        RMapCache<Object, Object> m = redisson.getMapCache("test");
        m.put("1", "2");
        m.put("3", "4");
        
        RTransaction transaction = redisson.createTransaction(TransactionOptions.defaults());
        RMapCache<Object, Object> map = transaction.getMapCache("test");
        assertThat(map.putIfAbsent("3", "2", 1, TimeUnit.SECONDS)).isEqualTo("4");
        assertThat(map.putIfAbsent("5", "6", 3, TimeUnit.SECONDS)).isNull();
        assertThat(map.putIfAbsent("5", "7", 1, TimeUnit.SECONDS)).isEqualTo("6");
        
        assertThat(m.get("3")).isEqualTo("4");
        assertThat(m.size()).isEqualTo(2);
        
        transaction.commit();
        
        assertThat(m.get("1")).isEqualTo("2");
        assertThat(m.get("3")).isEqualTo("4");
        assertThat(m.get("5")).isEqualTo("6");
        
        Thread.sleep(1500);
        
        assertThat(m.get("3")).isEqualTo("4");
        assertThat(m.get("5")).isEqualTo("6");
        
        Thread.sleep(1500);
        
        assertThat(m.get("3")).isEqualTo("4");
        assertThat(m.get("5")).isNull();
    }

    @Override
    protected RMap<String, String> getMap() {
        return redisson.getMapCache("test");
    }

    @Override
    protected RMap<String, String> getTransactionalMap(RTransaction transaction) {
        return transaction.getMapCache("test");
    }

    
}
