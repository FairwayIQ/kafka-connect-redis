/**
 * Copyright © 2017 Jeremy Custenborder (jcustenborder@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jcustenborder.kafka.connect.redis;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import org.apache.kafka.connect.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

abstract class SinkOperation {
  private static final Logger log = LoggerFactory.getLogger(SinkOperation.class);
  public static final SinkOperation NONE = new NoneOperation(null);

  public final Type type;
  protected final RedisSinkConnectorConfig config;

  SinkOperation(Type type, RedisSinkConnectorConfig config) {
    this.type = type;
    this.config = config;
  }

  public enum Type {
    SET,
    DELETE,
    PUBLISH,
    NONE
  }

  public abstract void add(byte[] key, byte[] value);

  public abstract void execute(RedisClusterAsyncCommands<byte[], byte[]> asyncCommands) throws InterruptedException;

  public abstract int size();

  protected void wait(RedisFuture<?> future) throws InterruptedException {
    log.debug("wait() - future = {}", future);
    if (!future.await(this.config.operationTimeoutMs, TimeUnit.MILLISECONDS)) {
      future.cancel(true);
      throw new RetriableException(
          String.format("Timeout after %s ms while waiting for operation to complete.", this.config.operationTimeoutMs)
      );
    }
  }

  public static SinkOperation create(Type type, RedisSinkConnectorConfig config, int size) {
    SinkOperation result;

    switch (type) {
      case SET:
        result = new SetOperation(config, size);
        break;
      case DELETE:
        result = new DeleteOperation(config, size);
        break;
      case PUBLISH:
        result = new PublishOperation(config, size);
        break;
      default:
        throw new IllegalStateException(
            String.format("%s is not a supported operation.", type)
        );
    }

    return result;
  }

  static class NoneOperation extends SinkOperation {
    NoneOperation(RedisSinkConnectorConfig config) {
      super(Type.NONE, config);
    }

    @Override
    public void add(byte[] key, byte[] value) {
      throw new UnsupportedOperationException(
          "This should never be called."
      );
    }

    @Override
    public void execute(RedisClusterAsyncCommands<byte[], byte[]> asyncCommands) {

    }

    @Override
    public int size() {
      return 0;
    }
  }

  static class SetOperation extends SinkOperation {
    final Map<byte[], byte[]> sets;

    SetOperation(RedisSinkConnectorConfig config, int size) {
      super(Type.SET, config);
      this.sets = new LinkedHashMap<>(size);
    }

    @Override
    public void add(byte[] key, byte[] value) {
      this.sets.put(key, value);
    }

    @Override
    public void execute(RedisClusterAsyncCommands<byte[], byte[]> asyncCommands) throws InterruptedException {
      log.debug("execute() - Calling mset with {} value(s)", this.sets.size());
      RedisFuture<?> future = asyncCommands.mset(this.sets);
      wait(future);
    }

    @Override
    public int size() {
      return this.sets.size();
    }
  }

  static class DeleteOperation extends SinkOperation {
    final List<byte[]> deletes;

    DeleteOperation(RedisSinkConnectorConfig config, int size) {
      super(Type.DELETE, config);
      this.deletes = new ArrayList<>(size);
    }

    @Override
    public void add(byte[] key, byte[] value) {
      this.deletes.add(key);
    }

    @Override
    public void execute(RedisClusterAsyncCommands<byte[], byte[]> asyncCommands) throws InterruptedException {
      log.debug("execute() - Calling del with {} value(s)", this.deletes.size());
      byte[][] deletes = this.deletes.toArray(new byte[this.deletes.size()][]);
      RedisFuture<?> future = asyncCommands.del(deletes);
      wait(future);
    }

    @Override
    public int size() {
      return this.deletes.size();
    }
  }

  static class PublishOperation extends SinkOperation {
    final List<byte[]> channels;
    final List<byte[]> messages;

    PublishOperation(RedisSinkConnectorConfig config, int size) {
      super(Type.PUBLISH, config);
      this.channels = new ArrayList<>(size);
      this.messages = new ArrayList<>(size);
    }

    @Override
    public void add(byte[] key, byte[] value) {
      this.channels.add(key);
      this.messages.add(value);
    }

    @Override
    public void execute(RedisClusterAsyncCommands<byte[], byte[]> asyncCommands) throws InterruptedException {
      log.debug("execute() - Calling publish with {} value(s)", this.messages.size());

      List<CompletableFuture> puplishings = IntStream
          .range(0, this.messages.size())
          .mapToObj(
              i -> asyncCommands.publish(this.channels.get(i), this.messages.get(i)).toCompletableFuture()
          ).collect(Collectors.toList());

      CompletableFuture<Void> batchPublish = CompletableFuture.allOf(puplishings.toArray(new CompletableFuture[puplishings.size()]));
      try {
        batchPublish.get(this.config.operationTimeoutMs, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        throw new RetriableException(
            String.format("Timeout after %s ms while waiting for operation to complete.", this.config.operationTimeoutMs)
        );
      }
    }

    @Override
    public int size() {
      return this.messages.size();
    }
  }
}
