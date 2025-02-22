/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdds.scm.proxy;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.conf.ConfigurationException;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.protocol.proto.SCMSecretKeyProtocolProtos.SCMSecretKeyProtocolService;
import org.apache.hadoop.hdds.ratis.ServerNotLeaderException;
import org.apache.hadoop.hdds.scm.ha.SCMHAUtils;
import org.apache.hadoop.hdds.scm.ha.SCMNodeInfo;
import org.apache.hadoop.hdds.utils.LegacyHadoopConfigurationSource;
import org.apache.hadoop.io.retry.FailoverProxyProvider;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Failover proxy provider for SCMSecretKeyProtocolService server.
 */
public class SecretKeyProtocolFailoverProxyProvider
    <T extends SCMSecretKeyProtocolService.BlockingInterface> implements
    FailoverProxyProvider<T>, Closeable {

  public static final Logger LOG =
      LoggerFactory.getLogger(SecretKeyProtocolFailoverProxyProvider.class);

  // scmNodeId -> ProxyInfo<rpcProxy>
  private final Map<String, ProxyInfo<T>> scmProxies;

  // scmNodeId -> SCMProxyInfo
  private final Map<String, SCMProxyInfo> scmProxyInfoMap;

  private List<String> scmNodeIds;

  // As SCM Client is shared across threads, performFailOver()
  // updates the currentProxySCMNodeId based on the updateLeaderNodeId which is
  // updated in shouldRetry(). When 2 or more threads run in parallel, the
  // RetryInvocationHandler will check the expectedFailOverCount
  // and not execute performFailOver() for one of them. So the other thread(s)
  // shall not call performFailOver(), it will call getProxy() which uses
  // currentProxySCMNodeId and returns the proxy.
  private volatile String currentProxySCMNodeId;
  private volatile int currentProxyIndex;


  private final ConfigurationSource conf;
  private final SCMClientConfig scmClientConfig;
  private final long scmVersion;

  private String scmServiceId;

  private final int maxRetryCount;
  private final long retryInterval;

  private final UserGroupInformation ugi;
  private final Class<T> proxyClazz;

  private String updatedLeaderNodeID = null;

  /**
   * Construct fail-over proxy provider for SCMSecurityProtocol Server.
   * @param conf
   * @param userGroupInformation
   */
  public SecretKeyProtocolFailoverProxyProvider(ConfigurationSource conf,
      UserGroupInformation userGroupInformation, Class<T> proxyClazz) {
    Preconditions.checkNotNull(userGroupInformation);
    this.ugi = userGroupInformation;
    this.conf = conf;
    this.proxyClazz = proxyClazz;
    this.scmVersion = RPC.getProtocolVersion(proxyClazz);

    this.scmProxies = new HashMap<>();
    this.scmProxyInfoMap = new HashMap<>();
    loadConfigs();

    this.currentProxyIndex = 0;
    currentProxySCMNodeId = scmNodeIds.get(currentProxyIndex);
    scmClientConfig = conf.getObject(SCMClientConfig.class);
    this.maxRetryCount = scmClientConfig.getRetryCount();
    this.retryInterval = scmClientConfig.getRetryInterval();
  }

  protected synchronized void loadConfigs() {
    List<SCMNodeInfo> scmNodeInfoList = SCMNodeInfo.buildNodeInfo(conf);
    scmNodeIds = new ArrayList<>();

    for (SCMNodeInfo scmNodeInfo : scmNodeInfoList) {
      if (scmNodeInfo.getScmSecurityAddress() == null) {
        throw new ConfigurationException("SCM Client Address could not " +
            "be obtained from config. Config is not properly defined");
      } else {
        InetSocketAddress scmSecurityAddress =
            NetUtils.createSocketAddr(scmNodeInfo.getScmSecurityAddress());

        scmServiceId = scmNodeInfo.getServiceId();
        String scmNodeId = scmNodeInfo.getNodeId();

        scmNodeIds.add(scmNodeId);
        SCMProxyInfo scmProxyInfo = new SCMProxyInfo(scmServiceId, scmNodeId,
            scmSecurityAddress);
        scmProxyInfoMap.put(scmNodeId, scmProxyInfo);
      }
    }
  }

  @Override
  public synchronized ProxyInfo<T> getProxy() {
    ProxyInfo<T> currentProxyInfo = scmProxies.get(getCurrentProxySCMNodeId());
    if (currentProxyInfo == null) {
      currentProxyInfo = createSCMProxy(getCurrentProxySCMNodeId());
    }
    return currentProxyInfo;
  }

  /**
   * Creates proxy object.
   */
  private ProxyInfo<T> createSCMProxy(String nodeId) {
    ProxyInfo<T> proxyInfo;
    SCMProxyInfo scmProxyInfo = scmProxyInfoMap.get(nodeId);
    InetSocketAddress address = scmProxyInfo.getAddress();
    try {
      T scmProxy = createSCMProxy(address);
      // Create proxyInfo here, to make it work with all Hadoop versions.
      proxyInfo = new ProxyInfo<T>(scmProxy, scmProxyInfo.toString());
      scmProxies.put(nodeId, proxyInfo);
      return proxyInfo;
    } catch (IOException ioe) {
      LOG.error("{} Failed to create RPC proxy to SCM at {}",
          this.getClass().getSimpleName(), address, ioe);
      throw new RuntimeException(ioe);
    }
  }

  private T createSCMProxy(InetSocketAddress scmAddress)
      throws IOException {
    Configuration hadoopConf =
        LegacyHadoopConfigurationSource.asHadoopConfiguration(conf);
    RPC.setProtocolEngine(hadoopConf, proxyClazz,
        ProtobufRpcEngine.class);

    // FailoverOnNetworkException ensures that the IPC layer does not attempt
    // retries on the same SCM in case of connection exception. This retry
    // policy essentially results in TRY_ONCE_THEN_FAIL.

    RetryPolicy connectionRetryPolicy = RetryPolicies
        .failoverOnNetworkException(0);

    return RPC.getProtocolProxy(proxyClazz,
        scmVersion, scmAddress, ugi,
        hadoopConf, NetUtils.getDefaultSocketFactory(hadoopConf),
        (int)scmClientConfig.getRpcTimeOut(), connectionRetryPolicy).getProxy();
  }


  @Override
  public synchronized void performFailover(T currentProxy) {
    if (updatedLeaderNodeID != null) {
      currentProxySCMNodeId = updatedLeaderNodeID;
    } else {
      nextProxyIndex();
    }
    LOG.debug("Failing over to next proxy. {}", getCurrentProxySCMNodeId());
  }

  public synchronized void performFailoverToAssignedLeader(String newLeader,
      Exception e) {
    ServerNotLeaderException snle =
        (ServerNotLeaderException) SCMHAUtils.getServerNotLeaderException(e);
    if (snle != null && snle.getSuggestedLeader() != null) {
      Optional< SCMProxyInfo > matchedProxyInfo =
          scmProxyInfoMap.values().stream().filter(
              proxyInfo -> NetUtils.getHostPortString(proxyInfo.getAddress())
                  .equals(snle.getSuggestedLeader())).findFirst();
      if (matchedProxyInfo.isPresent()) {
        newLeader = matchedProxyInfo.get().getNodeId();
        LOG.debug("Performing failover to suggested leader {}, nodeId {}",
            snle.getSuggestedLeader(), newLeader);
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Suggested leader {} does not match with any of the " +
                          "proxyInfo adress {}", snle.getSuggestedLeader(),
                  Arrays.toString(scmProxyInfoMap.values().toArray()));
        }
      }
    }
    assignLeaderToNode(newLeader);
  }


  private synchronized void assignLeaderToNode(String newLeaderNodeId) {
    if (!currentProxySCMNodeId.equals(newLeaderNodeId)) {
      if (scmProxyInfoMap.containsKey(newLeaderNodeId)) {
        updatedLeaderNodeID = newLeaderNodeId;
        LOG.debug("Updated LeaderNodeID {}", updatedLeaderNodeID);
      } else {
        updatedLeaderNodeID = null;
      }
    }
  }

  /**
   * Update the proxy index to the next proxy in the list.
   * @return the new proxy index
   */
  private synchronized void nextProxyIndex() {
    // round robin the next proxy
    currentProxyIndex = (getCurrentProxyIndex() + 1) % scmProxyInfoMap.size();
    currentProxySCMNodeId =  scmNodeIds.get(currentProxyIndex);
  }

  public RetryPolicy getRetryPolicy() {
    // Client will attempt up to maxFailovers number of failovers between
    // available SCMs before throwing exception.

    return (exception, retries, failovers, isIdempotentOrAtMostOnce) -> {

      if (LOG.isDebugEnabled()) {
        if (exception.getCause() != null) {
          LOG.debug("RetryProxy: SCM Security Server {}: {}: {}",
              getCurrentProxySCMNodeId(),
              exception.getCause().getClass().getSimpleName(),
              exception.getCause().getMessage());
        } else {
          LOG.debug("RetryProxy: SCM {}: {}", getCurrentProxySCMNodeId(),
              exception.getMessage());
        }
      }

      if (SCMHAUtils.checkRetriableWithNoFailoverException(exception)) {
        setUpdatedLeaderNodeID();
      } else {
        performFailoverToAssignedLeader(null, exception);
      }
      return SCMHAUtils
          .getRetryAction(failovers, retries, exception, maxRetryCount,
              getRetryInterval());
    };
  }

  public synchronized void setUpdatedLeaderNodeID() {
    this.updatedLeaderNodeID = getCurrentProxySCMNodeId();
  }

  @Override
  public Class<T> getInterface() {
    return proxyClazz;
  }

  @Override
  public synchronized void close() throws IOException {
    for (ProxyInfo<T> proxyInfo : scmProxies.values()) {
      if (proxyInfo.proxy != null) {
        RPC.stopProxy(proxyInfo.proxy);
      }
    }
  }

  public synchronized String getCurrentProxySCMNodeId() {
    return currentProxySCMNodeId;
  }

  public synchronized int getCurrentProxyIndex() {
    return currentProxyIndex;
  }

  private long getRetryInterval() {
    return retryInterval;
  }
}
