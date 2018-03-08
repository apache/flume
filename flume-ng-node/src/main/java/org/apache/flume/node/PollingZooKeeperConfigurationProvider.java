/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flume.node;

import java.io.IOException;
import java.util.function.Consumer;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.apache.flume.FlumeException;
import org.apache.flume.conf.FlumeConfiguration;
import org.apache.flume.lifecycle.LifecycleState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PollingZooKeeperConfigurationProvider extends
    AbstractZooKeeperConfigurationProvider {

  private static final Logger LOGGER = LoggerFactory
      .getLogger(PollingZooKeeperConfigurationProvider.class);

  private final CuratorFramework client;

  private NodeCache agentNodeCache;

  private FlumeConfiguration flumeConfiguration;

  public PollingZooKeeperConfigurationProvider(String agentName,
      String zkConnString, String basePath) {
    super(agentName, zkConnString, basePath);
    client = createClient();
    agentNodeCache = null;
    flumeConfiguration = null;
  }

  @Override
  public void registerConfigurationConsumer(
          Consumer<MaterializedConfiguration> configurationConsumer) {
    setConfigurationConsumer(configurationConsumer);
  }

  @Override
  protected FlumeConfiguration getFlumeConfiguration() {
    return flumeConfiguration;
  }

  @Override
  public void start() {
    LOGGER.debug("Starting...");
    try {
      client.start();
      try {
        agentNodeCache = new NodeCache(client, basePath + "/" + getAgentName());
        agentNodeCache.start();
        agentNodeCache.getListenable().addListener(new NodeCacheListener() {
          @Override
          public void nodeChanged() throws Exception {
            refreshConfiguration();
          }
        });
      } catch (Exception e) {
        client.close();
        throw e;
      }
    } catch (Exception e) {
      super.error();
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      } else {
        throw new FlumeException(e);
      }
    }
    super.start();
  }

  private void refreshConfiguration() throws IOException {
    LOGGER.info("Refreshing configuration from ZooKeeper");
    byte[] data = null;
    ChildData childData = agentNodeCache.getCurrentData();
    if (childData != null) {
      data = childData.getData();
    }
    flumeConfiguration = configFromBytes(data);
    notifyConfigurationConsumer(getConfiguration());
  }

  @Override
  public void stop() {
    LOGGER.debug("Stopping...");
    if (agentNodeCache != null) {
      try {
        agentNodeCache.close();
      } catch (IOException e) {
        LOGGER.warn("Encountered exception while stopping", e);
        super.error();
      }
    }

    try {
      client.close();
    } catch (Exception e) {
      LOGGER.warn("Error stopping Curator client", e);
      super.error();
    }

    if (getLifecycleState() != LifecycleState.ERROR) {
      super.stop();
    }
  }

}
