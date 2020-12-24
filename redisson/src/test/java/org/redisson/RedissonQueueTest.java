package org.redisson;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.Assert;
import org.junit.Test;
import org.redisson.api.RQueue;

public class RedissonQueueTest extends BaseTest {

    <T> RQueue<T> getQueue() {
        return redisson.getQueue("queue");
    }

    @Test
    public void testPollLimited() {
        RQueue<Integer> queue = getQueue();
        queue.addAll(Arrays.asList(1, 2, 3, 4, 5, 6, 7));
        List<Integer> elements = queue.poll(3);
        assertThat(elements).containsExactly(1, 2, 3);
        List<Integer> elements2 = queue.poll(10);
        assertThat(elements2).containsExactly(4, 5, 6, 7);
        List<Integer> elements3 = queue.poll(5);
        assertThat(elements3).isEmpty();
    }
    
    @Test
    public void testAddOffer() {
        RQueue<Integer> queue = getQueue();
        queue.add(1);
        queue.offer(2);
        queue.add(3);
        queue.offer(4);

        assertThat(queue).containsExactly(1, 2, 3, 4);
        Assert.assertEquals((Integer)1, queue.poll());
        assertThat(queue).containsExactly(2, 3, 4);
        Assert.assertEquals((Integer)2, queue.element());
    }

    @Test
    public void testRemove() {
        RQueue<Integer> queue = getQueue();
        queue.add(1);
        queue.add(2);
        queue.add(3);
        queue.add(4);

        queue.remove();
        queue.remove();

        assertThat(queue).containsExactly(3, 4);
        queue.remove();
        queue.remove();

        Assert.assertTrue(queue.isEmpty());
    }

    @Test(expected = NoSuchElementException.class)
    public void testRemoveEmpty() {
        RQueue<Integer> queue = getQueue();
        queue.remove();
    }

}
