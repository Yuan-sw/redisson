/**
 * Copyright (c) 2013-2020 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.client.protocol.decoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class ObjectMapReplayDecoder2 implements MultiDecoder<Map<Object, Object>> {

    @Override
    public Map<Object, Object> decode(List<Object> parts, State state) {
        List<List<Object>> list = (List<List<Object>>) (Object) parts;
        Map<Object, Object> result = new LinkedHashMap<Object, Object>(parts.size()/2);
        for (List<Object> entry : list) {
            result.put(entry.get(0), entry.get(1));
        }
        return result;
    }

    @Override
    public Decoder<Object> getDecoder(int paramNum, State state) {
        return null;
    }

}
