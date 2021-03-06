/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.clustermap;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.TestUtils.*;
import com.github.ambry.commons.ResponseHandler;
import com.github.ambry.commons.ServerErrorCode;
import com.github.ambry.config.ClusterMapConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.Utils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import org.apache.helix.HelixManager;
import org.apache.helix.InstanceType;
import org.apache.helix.model.InstanceConfig;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.github.ambry.clustermap.ClusterMapUtils.*;
import static com.github.ambry.clustermap.TestUtils.*;
import static org.junit.Assert.*;


/**
 * Tests the {@link HelixClusterManager} directly and also via the {@link CompositeClusterManager}.
 */
@RunWith(Parameterized.class)
public class HelixClusterManagerTest {
  private final HashMap<String, com.github.ambry.utils.TestUtils.ZkInfo> dcsToZkInfo = new HashMap<>();
  private final String dcs[] = new String[]{"DC0", "DC1"};
  private final TestUtils.TestHardwareLayout testHardwareLayout;
  private final TestPartitionLayout testPartitionLayout;
  private final String clusterNameStatic = "HelixClusterManagerTestCluster";
  private final String clusterNamePrefixInHelix = "Ambry-";
  private final ClusterMapConfig clusterMapConfig;
  private final MockHelixCluster helixCluster;
  private final String hostname;
  private final ClusterMap clusterManager;
  private MetricRegistry metricRegistry;
  private Map<String, Gauge> gauges;
  private Map<String, Counter> counters;
  private final boolean useComposite;
  private final String hardwareLayoutPath;
  private static final long CURRENT_XID = 64;

  // for verifying getPartitions() and getWritablePartitions()
  private static final String SPECIAL_PARTITION_CLASS = "specialPartitionClass";
  private final PartitionRangeCheckParams defaultRw;
  private final PartitionRangeCheckParams specialRw;
  private final PartitionRangeCheckParams defaultRo;
  private final PartitionRangeCheckParams specialRo;

  /**
   * Resource state associated with datanode, disk and replica.
   */
  enum ResourceState {
    Node_Up, Node_Down, Disk_Up, Disk_Down, Replica_Up, Replica_Down
  }

  @Parameterized.Parameters
  public static List<Object[]> data() {
    return Arrays.asList(new Object[][]{{false}, {true}});
  }

  /**
   * Construct the static layout files and use that to instantiate a {@link MockHelixCluster}.
   * Instantiate a {@link MockHelixManagerFactory} for use by the cluster manager.
   * @param useComposite whether or not the test are to be done for the {@link CompositeClusterManager}
   * @throws Exception
   */
  public HelixClusterManagerTest(boolean useComposite) throws Exception {
    this.useComposite = useComposite;
    String localDc = dcs[0];
    Random random = new Random();
    File tempDir = Files.createTempDirectory("helixClusterManager-" + random.nextInt(1000)).toFile();
    String tempDirPath = tempDir.getAbsolutePath();
    tempDir.deleteOnExit();
    int port = 2200;
    byte dcId = (byte) 0;
    for (String dcName : dcs) {
      dcsToZkInfo.put(dcName, new com.github.ambry.utils.TestUtils.ZkInfo(tempDirPath, dcName, dcId++, port++, false));
    }
    this.hardwareLayoutPath = tempDirPath + File.separator + "hardwareLayoutTest.json";
    String partitionLayoutPath = tempDirPath + File.separator + "partitionLayoutTest.json";
    String zkLayoutPath = tempDirPath + File.separator + "zkLayoutPath.json";
    JSONObject zkJson = constructZkLayoutJSON(dcsToZkInfo.values());
    testHardwareLayout = constructInitialHardwareLayoutJSON(clusterNameStatic);
    testPartitionLayout = constructInitialPartitionLayoutJSON(testHardwareLayout, 3, localDc);

    // for getPartitions() and getWritablePartitions() tests
    assertTrue("There should be more than 1 replica per partition in each DC for some of these tests to work",
        testPartitionLayout.replicaCountPerDc > 1);
    defaultRw = new PartitionRangeCheckParams(0, testPartitionLayout.partitionCount, DEFAULT_PARTITION_CLASS,
        PartitionState.READ_WRITE);
    // add 15 RW partitions for the special class
    specialRw =
        new PartitionRangeCheckParams(defaultRw.rangeEnd + 1, 15, SPECIAL_PARTITION_CLASS, PartitionState.READ_WRITE);
    testPartitionLayout.addNewPartitions(specialRw.count, SPECIAL_PARTITION_CLASS, PartitionState.READ_WRITE, localDc);
    // add 10 RO partitions for the default class
    defaultRo =
        new PartitionRangeCheckParams(specialRw.rangeEnd + 1, 10, DEFAULT_PARTITION_CLASS, PartitionState.READ_ONLY);
    testPartitionLayout.addNewPartitions(defaultRo.count, DEFAULT_PARTITION_CLASS, PartitionState.READ_ONLY, localDc);
    // add 5 RO partitions for the special class
    specialRo =
        new PartitionRangeCheckParams(defaultRo.rangeEnd + 1, 5, SPECIAL_PARTITION_CLASS, PartitionState.READ_ONLY);
    testPartitionLayout.addNewPartitions(specialRo.count, SPECIAL_PARTITION_CLASS, PartitionState.READ_ONLY, localDc);

    Utils.writeJsonObjectToFile(zkJson, zkLayoutPath);
    Utils.writeJsonObjectToFile(testHardwareLayout.getHardwareLayout().toJSONObject(), hardwareLayoutPath);
    Utils.writeJsonObjectToFile(testPartitionLayout.getPartitionLayout().toJSONObject(), partitionLayoutPath);
    helixCluster =
        new MockHelixCluster(clusterNamePrefixInHelix, hardwareLayoutPath, partitionLayoutPath, zkLayoutPath);
    for (PartitionId partitionId : testPartitionLayout.getPartitionLayout().getPartitions(null)) {
      if (partitionId.getPartitionState().equals(PartitionState.READ_ONLY)) {
        String partitionName = partitionId.toString();
        String helixPartitionName = partitionName.substring(partitionName.indexOf('[') + 1, partitionName.indexOf(']'));
        helixCluster.setPartitionState(helixPartitionName, PartitionState.READ_ONLY);
      }
    }

    hostname = "localhost";
    Properties props = new Properties();
    props.setProperty("clustermap.host.name", hostname);
    props.setProperty("clustermap.cluster.name", clusterNamePrefixInHelix + clusterNameStatic);
    props.setProperty("clustermap.datacenter.name", localDc);
    props.setProperty("clustermap.dcs.zk.connect.strings", zkJson.toString(2));
    props.setProperty("clustermap.current.xid", Long.toString(CURRENT_XID));
    clusterMapConfig = new ClusterMapConfig(new VerifiableProperties(props));
    MockHelixManagerFactory helixManagerFactory = new MockHelixManagerFactory(helixCluster, null);
    if (useComposite) {
      StaticClusterAgentsFactory staticClusterAgentsFactory =
          new StaticClusterAgentsFactory(clusterMapConfig, hardwareLayoutPath, partitionLayoutPath);
      metricRegistry = staticClusterAgentsFactory.getMetricRegistry();
      clusterManager = new CompositeClusterManager(staticClusterAgentsFactory.getClusterMap(),
          new HelixClusterManager(clusterMapConfig, hostname, helixManagerFactory, metricRegistry));
    } else {
      metricRegistry = new MetricRegistry();
      clusterManager = new HelixClusterManager(clusterMapConfig, hostname, helixManagerFactory, metricRegistry);
    }
  }

  /**
   * Close the cluster managers created.
   */
  @After
  public void after() {
    if (clusterManager != null) {
      clusterManager.close();
    }
  }

  /**
   * Test bad instantiation.
   * @throws Exception
   */
  @Test
  public void badInstantiationTest() throws Exception {
    // Good test happened in the constructor
    assertEquals(0L,
        metricRegistry.getGauges().get(HelixClusterManager.class.getName() + ".instantiationFailed").getValue());

    // Bad test
    Set<com.github.ambry.utils.TestUtils.ZkInfo> zkInfos = new HashSet<>(dcsToZkInfo.values());
    zkInfos.iterator().next().setPort(0);
    JSONObject invalidZkJson = constructZkLayoutJSON(zkInfos);
    Properties props = new Properties();
    props.setProperty("clustermap.host.name", hostname);
    props.setProperty("clustermap.cluster.name", clusterNamePrefixInHelix + clusterNameStatic);
    props.setProperty("clustermap.datacenter.name", dcs[0]);
    props.setProperty("clustermap.dcs.zk.connect.strings", invalidZkJson.toString(2));
    ClusterMapConfig invalidClusterMapConfig = new ClusterMapConfig(new VerifiableProperties(props));
    metricRegistry = new MetricRegistry();
    try {
      new HelixClusterManager(invalidClusterMapConfig, hostname, new MockHelixManagerFactory(helixCluster, null),
          metricRegistry);
      fail("Instantiation should have failed with invalid zk addresses");
    } catch (IOException e) {
      assertEquals(1L,
          metricRegistry.getGauges().get(HelixClusterManager.class.getName() + ".instantiationFailed").getValue());
    }

    metricRegistry = new MetricRegistry();
    try {
      new HelixClusterManager(clusterMapConfig, hostname,
          new MockHelixManagerFactory(helixCluster, new Exception("beBad")), metricRegistry);
      fail("Instantiation should fail with a HelixManager factory that throws exception on listener registrations");
    } catch (Exception e) {
      assertEquals(1L,
          metricRegistry.getGauges().get(HelixClusterManager.class.getName() + ".instantiationFailed").getValue());
      assertEquals("beBad", e.getCause().getMessage());
    }
  }

  /**
   * Tests all the interface methods.
   * @throws Exception
   */
  @Test
  public void basicInterfaceTest() throws Exception {
    for (String metricName : clusterManager.getMetricRegistry().getNames()) {
      System.out.println(metricName);
    }
    assertEquals("Incorrect local datacenter ID", 0, clusterManager.getLocalDatacenterId());
    testPartitionReplicaConsistency();
    testInvalidPartitionId();
    testDatacenterDatanodeReplicas();
    assertStateEquivalency();
  }

  /**
   * Test that everything works as expected in the presence of liveness changes initiated by Helix itself.
   * @throws Exception
   */
  @Test
  public void helixInitiatedLivenessChangeTest() throws Exception {
    // this test is not intended for the composite cluster manager.
    if (useComposite) {
      return;
    }
    // all instances are up initially.
    assertStateEquivalency();

    // Bring one instance down in each dc.
    for (String zkAddr : helixCluster.getZkAddrs()) {
      helixCluster.bringInstanceDown(helixCluster.getUpInstances(zkAddr).get(0));
    }

    assertStateEquivalency();

    // Bring all instances down in all dcs.
    helixCluster.bringAllInstancesDown();
    assertStateEquivalency();

    // Bring one instance up in each dc.
    for (String zkAddr : helixCluster.getZkAddrs()) {
      helixCluster.bringInstanceUp(helixCluster.getDownInstances(zkAddr).get(0));
    }
    assertStateEquivalency();
  }

  /**
   * Test that everything works as expected in the presence of liveness changes initiated by clients of the cluster
   * manager.
   */
  @Test
  public void clientInitiatedLivenessChangeTest() {
    ReplicaId replica = clusterManager.getWritablePartitionIds(null).get(0).getReplicaIds().get(0);
    DataNodeId dataNode = replica.getDataNodeId();
    assertTrue(clusterManager.getReplicaIds(dataNode).contains(replica));
    DiskId disk = replica.getDiskId();

    // Verify that everything is up in the beginning.
    assertFalse(replica.isDown());
    assertEquals(HardwareState.AVAILABLE, dataNode.getState());
    assertEquals(HardwareState.AVAILABLE, disk.getState());

    // Trigger node failure events for the replica.
    for (int i = 0; i < clusterMapConfig.clusterMapFixedTimeoutDatanodeErrorThreshold; i++) {
      clusterManager.onReplicaEvent(replica, ReplicaEventType.Node_Timeout);
    }

    // When node times out, all replicas and all disks on the node should also become unavailable.
    assertTrue(replica.isDown());
    assertEquals(HardwareState.UNAVAILABLE, dataNode.getState());
    assertEquals(HardwareState.UNAVAILABLE, disk.getState());

    // Trigger a successful node event to bring the resources up.
    clusterManager.onReplicaEvent(replica, ReplicaEventType.Node_Response);
    assertFalse(replica.isDown());
    assertEquals(HardwareState.AVAILABLE, dataNode.getState());
    assertEquals(HardwareState.AVAILABLE, disk.getState());

    // Similar tests for disks.
    for (int i = 0; i < clusterMapConfig.clusterMapFixedTimeoutDiskErrorThreshold; i++) {
      clusterManager.onReplicaEvent(replica, ReplicaEventType.Disk_Error);
    }
    assertTrue(replica.isDown());
    assertEquals(HardwareState.UNAVAILABLE, disk.getState());
    // node should still be available even on disk error.
    assertEquals(HardwareState.AVAILABLE, dataNode.getState());

    // Trigger a successful disk event to bring the resources up.
    clusterManager.onReplicaEvent(replica, ReplicaEventType.Disk_Ok);
    assertFalse(replica.isDown());
    assertEquals(HardwareState.AVAILABLE, dataNode.getState());
    assertEquals(HardwareState.AVAILABLE, disk.getState());

    if (!useComposite) {
      // Similar tests for replica.
      for (int i = 0; i < clusterMapConfig.clusterMapFixedTimeoutReplicaErrorThreshold; i++) {
        clusterManager.onReplicaEvent(replica, ReplicaEventType.Replica_Unavailable);
      }
      assertTrue(replica.isDown());
      assertEquals(HardwareState.AVAILABLE, disk.getState());
      // node should still be available even on disk error.
      assertEquals(HardwareState.AVAILABLE, dataNode.getState());

      // Trigger a successful replica event to bring the resources up.
      clusterManager.onReplicaEvent(replica, ReplicaEventType.Replica_Available);
      assertFalse(replica.isDown());
      assertEquals(HardwareState.AVAILABLE, dataNode.getState());
      assertEquals(HardwareState.AVAILABLE, disk.getState());
    }

    // The following does not do anything currently.
    clusterManager.onReplicaEvent(replica, ReplicaEventType.Partition_ReadOnly);
    assertStateEquivalency();
  }

  /**
   * Test that {@link ResponseHandler} works as expected in the presence of various types of server events. The test also
   * verifies the states of datanode, disk and replica are changed correctly based on server event.
   */
  @Test
  public void onServerEventTest() {
    if (useComposite) {
      return;
    }
    // Test configuration: we select the disk from one datanode and select the replica on that disk

    // Initial state: only disk is down; Server event: Replica_Unavailable; Expected result: disk becomes available again and replica becomes down
    mockServerEventsAndVerify(
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Down, ResourceState.Replica_Up},
        ServerErrorCode.Replica_Unavailable,
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Up, ResourceState.Replica_Down});

    // Initial state: only disk is down; Server event: Temporarily_Disabled; Expected result: disk becomes available again and replica becomes down
    mockServerEventsAndVerify(
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Down, ResourceState.Replica_Up},
        ServerErrorCode.Temporarily_Disabled,
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Up, ResourceState.Replica_Down});

    // Initial state: disk and replica are down; Server event: Replica_Unavailable; Expected result: disk becomes available again
    mockServerEventsAndVerify(
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Down, ResourceState.Replica_Down},
        ServerErrorCode.Replica_Unavailable,
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Up, ResourceState.Replica_Down});

    // Initial state: disk and replica are down; Server event: Temporarily_Disabled; Expected result: disk becomes available again
    mockServerEventsAndVerify(
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Down, ResourceState.Replica_Down},
        ServerErrorCode.Temporarily_Disabled,
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Up, ResourceState.Replica_Down});

    // Initial state: disk and replica are down; Server event: Partition_ReadOnly; Expected result: disk and replica become available again
    mockServerEventsAndVerify(
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Down, ResourceState.Replica_Down},
        ServerErrorCode.Partition_ReadOnly,
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Up, ResourceState.Replica_Up});

    // Initial state: everything is up; Server event: IO_Error; Expected result: disk and replica become unavailable
    mockServerEventsAndVerify(
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Up, ResourceState.Replica_Up},
        ServerErrorCode.IO_Error,
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Down, ResourceState.Replica_Down});

    // Initial state: everything is up; Server event: Disk_Unavailable; Expected result: disk and replica become unavailable
    mockServerEventsAndVerify(
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Up, ResourceState.Replica_Up},
        ServerErrorCode.Disk_Unavailable,
        new ResourceState[]{ResourceState.Node_Up, ResourceState.Disk_Down, ResourceState.Replica_Down});
  }

  /**
   * Test that the changes to the sealed states of replicas get reflected correctly in the cluster manager.
   * This also tests multiple InstanceConfig change callbacks (including multiple such callbacks tagged as
   * {@link org.apache.helix.NotificationContext.Type#INIT} and that they are dealt with correctly.
   */
  @Test
  public void sealedReplicaChangeTest() {
    if (useComposite) {
      return;
    }

    // all instances are up initially.
    assertStateEquivalency();

    AmbryPartition partition = (AmbryPartition) clusterManager.getWritablePartitionIds(null).get(0);
    List<String> instances = helixCluster.getInstancesForPartition((partition.toPathString()));
    helixCluster.setReplicaState(partition, instances.get(0), ReplicaStateType.SealedState, true, false);
    assertFalse("If any one replica is SEALED, the whole partition should be SEALED",
        clusterManager.getWritablePartitionIds(null).contains(partition));
    assertEquals("If any one replica is SEALED, the whole partition should be SEALED", PartitionState.READ_ONLY,
        partition.getPartitionState());
    helixCluster.setReplicaState(partition, instances.get(1), ReplicaStateType.SealedState, true, false);
    assertFalse("If any one replica is SEALED, the whole partition should be SEALED",
        clusterManager.getWritablePartitionIds(null).contains(partition));
    assertEquals("If any one replica is SEALED, the whole partition should be SEALED", PartitionState.READ_ONLY,
        partition.getPartitionState());
    helixCluster.setReplicaState(partition, instances.get(1), ReplicaStateType.SealedState, false, false);
    assertFalse("If any one replica is SEALED, the whole partition should be SEALED",
        clusterManager.getWritablePartitionIds(null).contains(partition));
    assertEquals("If any one replica is SEALED, the whole partition should be SEALED", PartitionState.READ_ONLY,
        partition.getPartitionState());
    helixCluster.setReplicaState(partition, instances.get(0), ReplicaStateType.SealedState, false, false);
    // At this point all replicas have been marked READ_WRITE. Now, the entire partition should be READ_WRITE.
    assertTrue("If no replica is SEALED, the whole partition should be Writable",
        clusterManager.getWritablePartitionIds(null).contains(partition));
    assertEquals("If no replica is SEALED, the whole partition should be Writable", PartitionState.READ_WRITE,
        partition.getPartitionState());
    assertStateEquivalency();
  }

  /**
   * Test that the changes to the stopped states of replicas get reflected correctly in the cluster manager.
   */
  @Test
  public void stoppedReplicaChangeTest() {
    if (useComposite) {
      return;
    }

    // all instances are up initially.
    assertStateEquivalency();

    AmbryPartition partition = (AmbryPartition) clusterManager.getWritablePartitionIds(null).get(0);
    List<String> instances = helixCluster.getInstancesForPartition((partition.toPathString()));
    // mark the replica on first instance as stopped
    helixCluster.setReplicaState(partition, instances.get(0), ReplicaStateType.StoppedState, true, false);
    int aliveCount = 0;
    for (AmbryReplica replica : partition.getReplicaIds()) {
      if (replica.isDown()) {
        assertEquals("Mismatch in hostname of instance where stopped replica resides", instances.get(0),
            replica.getDataNodeId().getHostname() + "_" + replica.getDataNodeId().getPort());
      } else {
        aliveCount++;
      }
    }
    assertEquals("Mismatch in number of alive replicas", instances.size() - 1, aliveCount);
    // unmark the stopped replica and no replica is in stopped state
    helixCluster.setReplicaState(partition, instances.get(0), ReplicaStateType.StoppedState, false, false);
    aliveCount = 0;
    for (AmbryReplica replica : partition.getReplicaIds()) {
      if (!replica.isDown()) {
        aliveCount++;
      }
    }
    assertEquals("Mismatch in number of alive replicas, all replicas should be up", instances.size(), aliveCount);
  }

  /**
   * Tests that if an InstanceConfig change notification is triggered with new instances, it is handled gracefully (no
   * exceptions). When we introduce support for dynamically adding them to the cluster map, this test should be enhanced
   * to actually verify that the new nodes are added.
   * @throws Exception
   */
  @Test
  public void dynamicNodeAdditionsTest() throws Exception {
    if (useComposite) {
      return;
    }
    testHardwareLayout.addNewDataNodes(1);
    Utils.writeJsonObjectToFile(testHardwareLayout.getHardwareLayout().toJSONObject(), hardwareLayoutPath);
    // this triggers a notification.
    helixCluster.upgradeWithNewHardwareLayout(hardwareLayoutPath);
  }

  /**
   * Tests that if the xid of an InstanceConfig change is greater than the current xid of the cluster manager, then that
   * change is ignored - both during initialization as well as with post-initialization InstanceConfig changes.
   */
  @Test
  public void xidTest() throws Exception {
    if (useComposite) {
      return;
    }
    // Close the one initialized in the constructor, as this test needs to test initialization flow as well.
    clusterManager.close();

    // Initialization path:
    MockHelixManagerFactory helixManagerFactory = new MockHelixManagerFactory(helixCluster, null);
    List<InstanceConfig> instanceConfigs = helixCluster.getAllInstanceConfigs();
    int instanceCount = instanceConfigs.size();
    InstanceConfig aheadInstanceConfig =
        instanceConfigs.get(com.github.ambry.utils.TestUtils.RANDOM.nextInt(instanceConfigs.size()));
    aheadInstanceConfig.getRecord().setSimpleField(XID_STR, Long.toString(CURRENT_XID + 1));
    HelixClusterManager clusterManager =
        new HelixClusterManager(clusterMapConfig, hostname, helixManagerFactory, new MetricRegistry());
    assertEquals(instanceCount - 1, clusterManager.getDataNodeIds().size());
    for (AmbryDataNode dataNode : clusterManager.getDataNodeIds()) {
      String instanceName = ClusterMapUtils.getInstanceName(dataNode.getHostname(), dataNode.getPort());
      assertFalse(instanceName.equals(aheadInstanceConfig.getInstanceName()));
    }

    // Post-initialization InstanceConfig change:
    InstanceConfig ignoreInstanceConfig =
        instanceConfigs.get(com.github.ambry.utils.TestUtils.RANDOM.nextInt(instanceConfigs.size()));
    String ignoreInstanceName = ignoreInstanceConfig.getInstanceName();
    ignoreInstanceConfig.getRecord().setSimpleField(XID_STR, Long.toString(CURRENT_XID + 2));

    AmbryReplica ignoreInstanceReplica = null;
    for (AmbryDataNode dataNode : clusterManager.getDataNodeIds()) {
      String instanceName = ClusterMapUtils.getInstanceName(dataNode.getHostname(), dataNode.getPort());
      if (instanceName.equals(ignoreInstanceName)) {
        ignoreInstanceReplica = clusterManager.getReplicaIds(dataNode).get(0);
        ignoreInstanceConfig.getRecord()
            .setListField(STOPPED_REPLICAS_STR,
                Collections.singletonList(ignoreInstanceReplica.getPartitionId().toPathString()));
        break;
      }
    }
    helixCluster.triggerInstanceConfigChangeNotification();
    // Because the XID was higher, the change reflecting this replica being stopped will not be absorbed.
    assertFalse(ignoreInstanceReplica.isDown());

    // Now advance the current xid of the cluster manager (simulated by moving back the xid in the InstanceConfig).
    ignoreInstanceConfig.getRecord().setSimpleField(XID_STR, Long.toString(CURRENT_XID - 2));
    helixCluster.triggerInstanceConfigChangeNotification();
    // Now the change should get absorbed.
    assertTrue(ignoreInstanceReplica.isDown());
  }

  /**
   * Test that the metrics in {@link HelixClusterManagerMetrics} are updated as expected. This also tests and ensures
   * coverage of the methods in {@link HelixClusterManager} that are used only by {@link HelixClusterManagerMetrics}.
   */
  @Test
  public void metricsTest() throws Exception {
    counters = clusterManager.getMetricRegistry().getCounters();
    gauges = clusterManager.getMetricRegistry().getGauges();

    // live instance trigger happens once initially.
    long instanceTriggerCount = dcs.length;

    // Bring one instance down in each dc in order to test the metrics more generally.
    for (String zkAddr : helixCluster.getZkAddrs()) {
      helixCluster.bringInstanceDown(helixCluster.getUpInstances(zkAddr).get(0));
      instanceTriggerCount++;
    }

    // trigger for live instance change event should have come in twice per dc - the initial one, and the one due to a
    // node brought up in each DC.
    assertEquals(instanceTriggerCount, getCounterValue("liveInstanceChangeTriggerCount"));
    assertEquals(dcs.length, getCounterValue("instanceConfigChangeTriggerCount"));
    assertEquals(helixCluster.getDataCenterCount(), getGaugeValue("datacenterCount"));
    assertEquals(helixCluster.getDownInstances().size() + helixCluster.getUpInstances().size(),
        getGaugeValue("dataNodeCount"));
    assertEquals(helixCluster.getDownInstances().size(), getGaugeValue("dataNodeDownCount"));
    assertEquals(helixCluster.getDiskCount(), getGaugeValue("diskCount"));
    assertEquals(helixCluster.getDiskDownCount(), getGaugeValue("diskDownCount"));
    assertEquals(helixCluster.getAllPartitions().size(), getGaugeValue("partitionCount"));
    assertEquals(helixCluster.getAllWritablePartitions().size(), getGaugeValue("partitionReadWriteCount"));
    assertEquals(helixCluster.getAllPartitions().size() - helixCluster.getAllWritablePartitions().size(),
        getGaugeValue("partitionSealedCount"));
    assertEquals(helixCluster.getDiskCapacity(), getGaugeValue("rawTotalCapacityBytes"));
    assertEquals(0L, getGaugeValue("isMajorityReplicasDownForAnyPartition"));
    assertEquals(0L,
        getGaugeValue(helixCluster.getDownInstances().iterator().next().replace('_', '-') + "-DataNodeResourceState"));
    assertEquals(1L,
        getGaugeValue(helixCluster.getUpInstances().iterator().next().replace('_', '-') + "-DataNodeResourceState"));
    helixCluster.bringAllInstancesDown();
    assertEquals(1L, getGaugeValue("isMajorityReplicasDownForAnyPartition"));
    if (useComposite) {
      helixCluster.bringAllInstancesUp();
      PartitionId partition = clusterManager.getWritablePartitionIds(null).get(0);
      assertEquals(0L, getCounterValue("getPartitionIdFromStreamMismatchCount"));

      ReplicaId replicaId = partition.getReplicaIds().get(0);
      assertEquals(0L, getCounterValue("getReplicaIdsMismatchCount"));

      // bring the replica down.
      for (int i = 0; i < clusterMapConfig.clusterMapFixedTimeoutDiskErrorThreshold; i++) {
        clusterManager.onReplicaEvent(replicaId, ReplicaEventType.Disk_Error);
      }
      clusterManager.getWritablePartitionIds(null);
      assertEquals(0L, getCounterValue("getPartitionIdFromStreamMismatchCount"));

      InputStream partitionStream = new ByteBufferInputStream(ByteBuffer.wrap(partition.getBytes()));
      clusterManager.getPartitionIdFromStream(partitionStream);
      assertEquals(0L, getCounterValue("getWritablePartitionIdsMismatchCount"));

      clusterManager.hasDatacenter("invalid");
      clusterManager.hasDatacenter(dcs[0]);
      assertEquals(0L, getCounterValue("hasDatacenterMismatchCount"));

      DataNodeId dataNodeId = clusterManager.getDataNodeIds().get(0);
      assertEquals(0L, getCounterValue("getDataNodeIdsMismatchCount"));

      clusterManager.getDataNodeId(dataNodeId.getHostname(), dataNodeId.getPort());
      assertEquals(0L, getCounterValue("getDataNodeIdMismatchCount"));
    }
  }

  /**
   * Tests for {@link PartitionLayout#getPartitions(String)} and {@link PartitionLayout#getWritablePartitions(String)}.
   * @throws JSONException
   */
  @Test
  public void getPartitionsTest() {
    // "good" cases for getPartitions() and getWritablePartitions() only
    // getPartitions(), class null
    List<? extends PartitionId> returnedPartitions = clusterManager.getAllPartitionIds(null);
    checkReturnedPartitions(returnedPartitions, Arrays.asList(defaultRw, defaultRo, specialRw, specialRo));
    // getWritablePartitions(), class null
    returnedPartitions = clusterManager.getWritablePartitionIds(null);
    checkReturnedPartitions(returnedPartitions, Arrays.asList(defaultRw, specialRw));

    // getPartitions(), class default
    returnedPartitions = clusterManager.getAllPartitionIds(DEFAULT_PARTITION_CLASS);
    checkReturnedPartitions(returnedPartitions, Arrays.asList(defaultRw, defaultRo));
    // getWritablePartitions(), class default
    returnedPartitions = clusterManager.getWritablePartitionIds(DEFAULT_PARTITION_CLASS);
    checkReturnedPartitions(returnedPartitions, Collections.singletonList(defaultRw));
  }

  // Helpers

  /**
   * The helper method sets up initial states for datanode, disk and replica. Then it triggers specified server event and
   * verifies the states of datanode, disk and replica are expected after event.
   * @param initialStates the initial states for datanode, disk and replica (default order).
   * @param serverErrorCode the {@link ServerErrorCode} received for mocking event.
   * @param expectedStates the expected states for datanode, disk and replica (default order).
   */
  private void mockServerEventsAndVerify(ResourceState[] initialStates, ServerErrorCode serverErrorCode,
      ResourceState[] expectedStates) {
    ResponseHandler handler = new ResponseHandler(clusterManager);
    ReplicaId replica = clusterManager.getWritablePartitionIds(null).get(0).getReplicaIds().get(0);
    DataNodeId dataNode = replica.getDataNodeId();
    assertTrue(clusterManager.getReplicaIds(dataNode).contains(replica));
    DiskId disk = replica.getDiskId();

    // Verify that everything is up in the beginning.
    assertFalse(replica.isDown());
    assertEquals(HardwareState.AVAILABLE, dataNode.getState());
    assertEquals(HardwareState.AVAILABLE, disk.getState());

    // Mock initial states for node, disk and replica
    if (initialStates[0] == ResourceState.Node_Down) {
      for (int i = 0; i < clusterMapConfig.clusterMapFixedTimeoutDatanodeErrorThreshold; i++) {
        clusterManager.onReplicaEvent(replica, ReplicaEventType.Node_Timeout);
      }
    }
    if (initialStates[1] == ResourceState.Disk_Down) {
      for (int i = 0; i < clusterMapConfig.clusterMapFixedTimeoutDiskErrorThreshold; i++) {
        clusterManager.onReplicaEvent(replica, ReplicaEventType.Disk_Error);
      }
    }
    if (initialStates[2] == ResourceState.Replica_Down) {
      for (int i = 0; i < clusterMapConfig.clusterMapFixedTimeoutReplicaErrorThreshold; i++) {
        clusterManager.onReplicaEvent(replica, ReplicaEventType.Replica_Unavailable);
      }
    }

    // Make sure node, disk and replica match specified initial states
    if (dataNode.getState() == HardwareState.AVAILABLE && disk.getState() == HardwareState.AVAILABLE) {
      // Since replica.isDown() will check the state of disk, if we try to mock disk is down and replica is up, we should
      // skip this check for initial state. Only when node and disk are up, we check the initial state of replica.
      assertEquals(initialStates[2], replica.isDown() ? ResourceState.Replica_Down : ResourceState.Replica_Up);
    }
    if (dataNode.getState() == HardwareState.AVAILABLE) {
      assertEquals(initialStates[1],
          disk.getState() == HardwareState.UNAVAILABLE ? ResourceState.Disk_Down : ResourceState.Disk_Up);
    }
    assertEquals(initialStates[0],
        dataNode.getState() == HardwareState.UNAVAILABLE ? ResourceState.Node_Down : ResourceState.Node_Up);

    // Trigger server event
    handler.onEvent(replica, serverErrorCode);

    // Verify node, disk and replica match expected states after server event
    assertEquals(expectedStates[2], replica.isDown() ? ResourceState.Replica_Down : ResourceState.Replica_Up);
    assertEquals(expectedStates[1],
        disk.getState() == HardwareState.UNAVAILABLE ? ResourceState.Disk_Down : ResourceState.Disk_Up);
    assertEquals(expectedStates[0],
        dataNode.getState() == HardwareState.UNAVAILABLE ? ResourceState.Node_Down : ResourceState.Node_Up);
  }

  /**
   * Get the counter value for the metric in {@link HelixClusterManagerMetrics} with the given suffix.
   * @param suffix the suffix of the metric that distinguishes it from other metrics in the class.
   * @return the value of the counter.
   */
  private long getCounterValue(String suffix) {
    return counters.get(HelixClusterManager.class.getName() + "." + suffix).getCount();
  }

  /**
   * Get the gauge value for the metric in {@link HelixClusterManagerMetrics} with the given suffix.
   * @param suffix the suffix of the metric that distinguishes it from other metrics in the class.
   * @return the value of the gauge.
   */
  private long getGaugeValue(String suffix) {
    return (long) gauges.get(HelixClusterManager.class.getName() + "." + suffix).getValue();
  }

  /**
   * Tests that the writable partitions returned by the {@link HelixClusterManager} is the same as the writable
   * partitions in the cluster.
   */
  private void testWritablePartitions() {
    Set<String> writableInClusterManager = new HashSet<>();
    for (PartitionId partition : clusterManager.getWritablePartitionIds(null)) {
      String partitionStr =
          useComposite ? ((Partition) partition).toPathString() : ((AmbryPartition) partition).toPathString();
      writableInClusterManager.add(partitionStr);
    }
    Set<String> writableInCluster = helixCluster.getWritablePartitions();
    if (writableInCluster.isEmpty()) {
      writableInCluster = helixCluster.getAllWritablePartitions();
    }
    assertEquals(writableInCluster, writableInClusterManager);
  }

  /**
   * Tests that all partitions returned by the {@link HelixClusterManager} is equivalent to all
   * partitions in the cluster.
   */
  private void testAllPartitions() {
    Set<String> partitionsInClusterManager = new HashSet<>();
    for (PartitionId partition : clusterManager.getAllPartitionIds(null)) {
      String partitionStr =
          useComposite ? ((Partition) partition).toPathString() : ((AmbryPartition) partition).toPathString();
      partitionsInClusterManager.add(partitionStr);
    }
    Set<String> allPartitions = helixCluster.getAllPartitions();
    assertEquals(allPartitions, partitionsInClusterManager);
  }

  /**
   * Tests that the replica count and replica to partition id mappings as reported by the cluster manager is the same as
   * those in the cluster.
   */
  private void testPartitionReplicaConsistency() throws Exception {
    for (PartitionId partition : clusterManager.getWritablePartitionIds(null)) {
      assertEquals(testPartitionLayout.getTotalReplicaCount(), partition.getReplicaIds().size());
      InputStream partitionStream = new ByteBufferInputStream(ByteBuffer.wrap(partition.getBytes()));
      PartitionId fetchedPartition = clusterManager.getPartitionIdFromStream(partitionStream);
      assertEquals(partition, fetchedPartition);
    }
  }

  /**
   * Test that invalid partition id deserialization fails as expected.
   */
  private void testInvalidPartitionId() {
    PartitionId partition = clusterManager.getWritablePartitionIds(null).get(0);
    try {
      byte[] fakePartition = Arrays.copyOf(partition.getBytes(), partition.getBytes().length);
      for (int i = fakePartition.length; i > fakePartition.length - Long.SIZE / Byte.SIZE; i--) {
        fakePartition[i - 1] = (byte) 0xff;
      }
      InputStream partitionStream = new ByteBufferInputStream(ByteBuffer.allocate(fakePartition.length));
      clusterManager.getPartitionIdFromStream(partitionStream);
      fail("partition id deserialization should have failed");
    } catch (IOException e) {
      // OK
    }
  }

  /**
   * Test clustermap interface methods related to datanodes and datacenter.
   */
  private void testDatacenterDatanodeReplicas() {
    for (Datacenter datacenter : testHardwareLayout.getHardwareLayout().getDatacenters()) {
      assertTrue(clusterManager.hasDatacenter(datacenter.getName()));
      for (DataNode dataNode : datacenter.getDataNodes()) {
        DataNodeId dataNodeId = clusterManager.getDataNodeId(dataNode.getHostname(), dataNode.getPort());
        assertEquals(dataNode.toString(), dataNodeId.toString());
        if (!useComposite) {
          try {
            clusterManager.getReplicaIds(dataNode);
            fail("HelixClusterManager methods should throw when passed in a static manager datanode");
          } catch (IllegalArgumentException e) {
            // OK
          }
        } else {
          clusterManager.getReplicaIds(dataNode);
        }
        for (ReplicaId replica : clusterManager.getReplicaIds(dataNodeId)) {
          assertEquals(dataNodeId, replica.getDataNodeId());
        }
      }
    }
  }

  /**
   * Assert that the state of datanodes in the cluster manager's view are consistent with their actual states in the
   * cluster.
   */
  private void assertStateEquivalency() {
    Set<String> upInstancesInCluster = helixCluster.getUpInstances();
    Set<String> downInstancesInCluster = helixCluster.getDownInstances();

    Set<String> upInstancesInClusterManager = new HashSet<>();
    Set<String> downInstancesInClusterManager = new HashSet<>();
    for (DataNodeId dataNode : clusterManager.getDataNodeIds()) {
      if (dataNode.getState() == HardwareState.UNAVAILABLE) {
        assertTrue("Datanode should not be a duplicate", downInstancesInClusterManager.add(
            ClusterMapUtils.getInstanceName(dataNode.getHostname(), dataNode.getPort())));
      } else {
        assertTrue("Datanode should not be a duplicate", upInstancesInClusterManager.add(
            ClusterMapUtils.getInstanceName(dataNode.getHostname(), dataNode.getPort())));
      }
    }
    assertEquals(downInstancesInCluster, downInstancesInClusterManager);
    assertEquals(upInstancesInCluster, upInstancesInClusterManager);
    testWritablePartitions();
    testAllPartitions();
  }

  /**
   * A Mock implementaion of {@link HelixFactory} that returns the {@link MockHelixManager}
   */
  private static class MockHelixManagerFactory extends HelixFactory {
    private final MockHelixCluster helixCluster;
    private final Exception beBadException;

    /**
     * Construct this factory
     * @param helixCluster the {@link MockHelixCluster} that this factory's manager will be associated with.
     * @param beBadException the {@link Exception} that the Helix Manager constructed by this factory will throw.
     */
    MockHelixManagerFactory(MockHelixCluster helixCluster, Exception beBadException) {
      this.helixCluster = helixCluster;
      this.beBadException = beBadException;
    }

    /**
     * Return a {@link MockHelixManager}
     * @param clusterName the name of the cluster for which the manager is to be gotten.
     * @param instanceName the name of the instance on whose behalf the manager is to be gotten.
     * @param instanceType the {@link InstanceType} of the requester.
     * @param zkAddr the address identifying the zk service to which this request is to be made.
     * @return the {@link MockHelixManager}
     */
    HelixManager getZKHelixManager(String clusterName, String instanceName, InstanceType instanceType, String zkAddr) {
      if (helixCluster.getZkAddrs().contains(zkAddr)) {
        return new MockHelixManager(instanceName, instanceType, zkAddr, helixCluster, beBadException);
      } else {
        throw new IllegalArgumentException("Invalid ZkAddr");
      }
    }
  }
}

