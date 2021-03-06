/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.andes.server.cluster;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.IdGenerator;
import com.hazelcast.core.Member;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.andes.configuration.AndesConfigurationManager;
import org.wso2.andes.configuration.enums.AndesConfiguration;
import org.wso2.andes.kernel.AndesContext;
import org.wso2.andes.kernel.AndesException;
import org.wso2.andes.kernel.slot.SlotCoordinationConstants;
import org.wso2.andes.server.cluster.coordination.CoordinationConstants;
import org.wso2.andes.server.cluster.coordination.hazelcast.AndesMembershipListener;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hazelcast based cluster agent implementation
 */
public class HazelcastClusterAgent implements ClusterAgent {

    /**
     * Class logger
     */
    private Log log = LogFactory.getLog(HazelcastClusterAgent.class);

    /**
     * Used to identify coordinator change event
     */
    private final AtomicBoolean isCoordinator;

    /**
     * Hazelcast instance used to communicate with the hazelcast cluster
     */
    private final HazelcastInstance hazelcastInstance;

    /**
     * Unique id of local member used for message ID generation
     */
    private int uniqueIdOfLocalMember;

    /**
     * registration id for membership listner
     */
    private String listenerRegistrationId;

    /**
     * Cluster manager used to indicate membership change events
     */
    private ClusterManager manager;

    /**
     * Hold coordinator information
     */
    private IMap<String, String> coordinatorNodeDetailsMap;

    /**
     * Hold thrift server details
     */
    private IMap<Object, Object> thriftServerDetailsMap;

    /**
     * Attribute name used for storing node id inside hazelcast member
     */
    private static final String NODE_ID_ATTRIBUTE = "nodeId";

    public HazelcastClusterAgent(HazelcastInstance hazelcastInstance) {
        this.hazelcastInstance = hazelcastInstance;
        this.isCoordinator = new AtomicBoolean(false);
    }

    /**
     * Membership listener calls this method when current node is elected as the coordinator
     */
    public void localNodeElectedAsCoordinator() {
        updateThriftCoordinatorDetailsToMap();
        updateCoordinatorNodeDetailMap();

        manager.localNodeElectedAsCoordinator();
    }

    /**
     * Membership listener calls this method when a new node joins the cluster
     *
     * @param member
     *         New member
     */
    public void memberAdded(Member member) {
        checkAndNotifyCoordinatorChange();
        manager.memberAdded(CoordinationConstants.NODE_NAME_PREFIX + member.getSocketAddress());
    }

    /**
     * Membership listener calls this method when a node leaves the cluster
     *
     * @param member
     *         member who left
     * @throws AndesException
     */
    public void memberRemoved(Member member) throws AndesException {
        checkAndNotifyCoordinatorChange();
        manager.memberRemoved(getIdOfNode(member));
    }

    /**
     * Get id of the give node
     *
     * @param node
     *         Hazelcast member node
     * @return id of the node
     */
    public String getIdOfNode(Member node) {
        return node.getStringAttribute(NODE_ID_ATTRIBUTE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getUniqueIdForLocalNode() {
        return uniqueIdOfLocalMember;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCoordinator() {
        Member oldestMember = hazelcastInstance.getCluster().getMembers().iterator().next();

        return oldestMember.localMember();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(ClusterManager manager) throws AndesException{
        this.manager = manager;

        hazelcastInstance.getCluster().getLocalMember().setStringAttribute(NODE_ID_ATTRIBUTE, getLocalNodeIdentifier());

        //Checking for duplicate node ids in cluster
        Set<Member> members = hazelcastInstance.getCluster().getMembers();
        Member localMember = hazelcastInstance.getCluster().getLocalMember();
        for (Member member : members) {
            if (getIdOfNode(member).equals(getLocalNodeIdentifier()) &&
                localMember != member) {
                throw new AndesException(
                        "Another node with the same node id: " + getLocalNodeIdentifier() +
                        " found in the cluster. Cannot start the node.");
            }
        }

        // Register listener for membership changes
        listenerRegistrationId = hazelcastInstance.getCluster()
                                                  .addMembershipListener(new AndesMembershipListener(this));

        coordinatorNodeDetailsMap = hazelcastInstance.getMap(CoordinationConstants.COORDINATOR_NODE_DETAILS_MAP_NAME);
        thriftServerDetailsMap = hazelcastInstance.getMap(CoordinationConstants.THRIFT_SERVER_DETAILS_MAP_NAME);

        // Generate a unique id for this node for message id generation
        IdGenerator idGenerator = this.hazelcastInstance.getIdGenerator(
                CoordinationConstants.HAZELCAST_ID_GENERATOR_NAME);
        this.uniqueIdOfLocalMember = (int) idGenerator.newId();
        if (log.isDebugEnabled()) {
            log.debug("Unique ID generation for message ID generation:" + uniqueIdOfLocalMember);
        }

        memberAdded(hazelcastInstance.getCluster().getLocalMember());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        hazelcastInstance.getCluster().removeMembershipListener(listenerRegistrationId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLocalNodeIdentifier() {
        String nodeId;

        // Get Node ID configured by user in broker.xml (if not "default" we must use it as the ID)
        nodeId = AndesConfigurationManager.readValue(AndesConfiguration.COORDINATION_NODE_ID);

        // If the config value is "default" we must generate the ID
        if (AndesConfiguration.COORDINATION_NODE_ID.get().getDefaultValue().equals(nodeId)) {
            Member localMember = hazelcastInstance.getCluster().getLocalMember();
            nodeId = CoordinationConstants.NODE_NAME_PREFIX + localMember.getSocketAddress();
        }

        return nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getAllNodeIdentifiers() {
        Set<Member> members = hazelcastInstance.getCluster().getMembers();
        List<String> nodeIDList = new ArrayList<>();
        for (Member member : members) {
            nodeIDList.add(getIdOfNode(member));
        }

        return nodeIDList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CoordinatorInformation getCoordinatorDetails() {
        String ipAddress = coordinatorNodeDetailsMap.get(
                SlotCoordinationConstants.CLUSTER_COORDINATOR_SERVER_IP);
        String port = coordinatorNodeDetailsMap.get(SlotCoordinationConstants.CLUSTER_COORDINATOR_SERVER_PORT);

        return new CoordinatorInformation(ipAddress, port);
    }

    /**
     * Set coordinator's thrift server IP and port in hazelcast map.
     */
    private void updateThriftCoordinatorDetailsToMap() {

        String thriftCoordinatorServerIP = AndesContext.getInstance().getThriftServerHost();
        int thriftCoordinatorServerPort = AndesContext.getInstance().getThriftServerPort();


        log.info("This node is elected as the Slot Coordinator. Registering " + thriftCoordinatorServerIP + ":"
                 + thriftCoordinatorServerPort);
        thriftServerDetailsMap.put(SlotCoordinationConstants.THRIFT_COORDINATOR_SERVER_IP, thriftCoordinatorServerIP);
        thriftServerDetailsMap.put(SlotCoordinationConstants.THRIFT_COORDINATOR_SERVER_PORT,
                                   Integer.toString(thriftCoordinatorServerPort));
    }

    /**
     * Sets coordinator's hostname and port in {@link org.wso2.andes.server.cluster.coordination
     * .CoordinationConstants#COORDINATOR_NODE_DETAILS_MAP_NAME} hazelcast map.
     */
    private void updateCoordinatorNodeDetailMap() {
        // Adding cluster coordinator's node IP and port
        Member localMember = hazelcastInstance.getCluster().getLocalMember();
        coordinatorNodeDetailsMap.put(SlotCoordinationConstants.CLUSTER_COORDINATOR_SERVER_IP,
                                      localMember.getSocketAddress().getAddress().getHostAddress());
        coordinatorNodeDetailsMap.put(SlotCoordinationConstants.CLUSTER_COORDINATOR_SERVER_PORT,
                                      Integer.toString(localMember.getSocketAddress().getPort()));
    }

    /**
     * Check if the current node is the new coordinator and notify cluster manager.
     */
    private void checkAndNotifyCoordinatorChange() {
        if (isCoordinator() && isCoordinator.compareAndSet(false, true)) {
            localNodeElectedAsCoordinator();
        } else {
            isCoordinator.set(false);
        }
    }


    /**
     * Gets address of all the members in the cluster. i.e address:port
     *
     * @return A list of address of the nodes in a cluster
     */
    public List<String> getAllClusterNodeAddresses() {
        List<String> addresses = new ArrayList<>();
        if (AndesContext.getInstance().isClusteringEnabled()) {
            for (Member member : hazelcastInstance.getCluster().getMembers()) {
                InetSocketAddress socket = member.getSocketAddress();
                addresses.add(getIdOfNode(member) + ","
                              + socket.getAddress().getHostAddress() + "," + socket.getPort());
            }
        }
        return addresses;
    }
}
